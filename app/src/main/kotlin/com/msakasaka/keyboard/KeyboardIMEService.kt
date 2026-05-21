package com.msakasaka.keyboard

import android.content.SharedPreferences
import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.msakasaka.keyboard.engine.Dictionary
import com.msakasaka.keyboard.engine.InputMode
import com.msakasaka.keyboard.engine.InputState
import com.msakasaka.keyboard.engine.JapaneseInputEngine
import com.msakasaka.keyboard.settings.KeyboardSettings
import com.msakasaka.keyboard.ui.KeyboardListener
import com.msakasaka.keyboard.ui.MainKeyboardView
import com.msakasaka.keyboard.util.AIPrediction
import com.msakasaka.keyboard.util.AutoUpdater
import com.msakasaka.keyboard.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KeyboardIMEService : InputMethodService() {

    private lateinit var mainView: MainKeyboardView
    private lateinit var engine: JapaneseInputEngine
    private lateinit var dictionary: Dictionary
    private lateinit var settings: KeyboardSettings
    private lateinit var clipboardHelper: ClipboardHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var aiPredictionJob: Job? = null
    private var isShowingAiPredictions = false

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "key_height_dp" -> mainView.applyKeyHeight(settings.keyHeightDp)
            "key_width_scale" -> mainView.applyKeyWidthScale(settings.keyWidthScale)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dictionary = Dictionary(this)
        engine = JapaneseInputEngine(dictionary)
        settings = KeyboardSettings(this)
        clipboardHelper = ClipboardHelper(this)
        settings.registerListener(settingsListener)

        // 辞書を事前ロード（最初のキー入力で候補が即表示されるよう）
        serviceScope.launch { dictionary.ensureLoaded() }

        // 自動アップデート確認（1日1回）
        serviceScope.launch {
            try { AutoUpdater(this@KeyboardIMEService).checkAndDownloadIfNeeded() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        settings.unregisterListener(settingsListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        mainView = MainKeyboardView(this)
        mainView.applyKeyHeight(settings.keyHeightDp)
        mainView.applyKeyWidthScale(settings.keyWidthScale)

        engine.onStateChanged = { snapshot ->
            when (snapshot.state) {
                InputState.COMPOSING -> {
                    isShowingAiPredictions = false
                    aiPredictionJob?.cancel()
                    currentInputConnection?.setComposingText(snapshot.composing, 1)
                    mainView.candidateView.candidates = snapshot.candidates
                }
                InputState.CONVERTING -> {
                    currentInputConnection?.setComposingText(snapshot.composing, 1)
                    mainView.candidateView.candidates = snapshot.candidates
                    mainView.candidateView.selectedIndex = snapshot.selectedCandidateIndex
                }
                InputState.IDLE -> {
                    if (!isShowingAiPredictions) {
                        mainView.candidateView.candidates = emptyList()
                    }
                }
            }
        }

        // commitText() replaces any current composing region — no finishComposingText() needed
        mainView.candidateView.onCandidateClick = { index ->
            if (engine.state == InputState.IDLE && isShowingAiPredictions) {
                val prediction = mainView.candidateView.candidates.getOrNull(index) ?: return@onCandidateClick
                isShowingAiPredictions = false
                currentInputConnection?.commitText("$prediction ", 1)
                triggerAiPrediction()
            } else {
                val selected = engine.selectCandidate(index)
                currentInputConnection?.commitText(selected, 1)
            }
        }

        val keyListener = object : KeyboardListener {
            override fun onChar(ch: String) = handleChar(ch)
            override fun onBackspace() = handleBackspace()
            override fun onEnter() = handleEnter()
            override fun onSpace() = handleSpace()
            override fun onConvert() = handleConvert()
            override fun onModifier() = handleModifier()
            override fun onSwitchMode() = handleSwitchMode()
            override fun onNumberMode() = handleNumberMode()
            override fun onExitNumberMode() = handleExitNumberMode()
            override fun onClipboardOpen() = handleClipboard()
            override fun onCursorLeft() = handleCursorLeft()
            override fun onCursorRight() = handleCursorRight()
        }
        mainView.flickKeyboard.listener = keyListener
        mainView.qwertyKeyboard.listener = keyListener
        mainView.numberKeyboard.listener = keyListener

        mainView.clipboardPanel.onImageSelected = { uri, mimeType ->
            commitImage(uri, mimeType)
            mainView.hideClipboard()
        }
        mainView.clipboardPanel.onClose = { mainView.hideClipboard() }

        return mainView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine.reset()
    }

    override fun onFinishInput() {
        engine.reset()
        super.onFinishInput()
    }

    // ────────────── key handlers ──────────────

    private fun handleChar(ch: String) {
        if (engine.mode == InputMode.ENGLISH) {
            if (ch.matches(Regex("[a-zA-Z]"))) {
                // アルファベットはコンポジションに溜めて予測変換
                // 単語先頭かつ文の先頭なら自動的に大文字化（Shift入力済みなら維持）
                val input = if (engine.composing.isEmpty() && ch[0].isLowerCase() && shouldAutoCapitalize()) {
                    ch.uppercase()
                } else {
                    ch
                }
                engine.appendChar(input)
            } else {
                // 記号・数字はコンポジション確定→記号入力
                commitEnglishComposing()
                currentInputConnection?.commitText(ch, 1)
            }
            return
        }
        engine.appendChar(ch)
    }

    /** カーソル直前の文脈を見て、次の英字を大文字にすべきか判定 */
    private fun shouldAutoCapitalize(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return true
        val trimmed = before.trimEnd { it == ' ' || it == '\t' }
        if (trimmed.isEmpty()) return true  // 文書の先頭
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?' || last == '\n'
    }

    /** 英単語に対する標準的な自動補正（"i" → "I" など） */
    private fun autoCorrectEnglish(word: String): String {
        val lowered = word.lowercase()
        // 単独の "i" → "I"
        if (lowered == "i") return "I"
        // 標準的な縮約形補正（モバイルキーボード標準動作）
        val contractions = mapOf(
            "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
            "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't",
            "werent" to "weren't", "dont" to "don't", "doesnt" to "doesn't",
            "didnt" to "didn't", "cant" to "can't", "couldnt" to "couldn't",
            "wont" to "won't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
            "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
            "youre" to "you're", "youve" to "you've", "youll" to "you'll",
            "youd" to "you'd", "theyre" to "they're", "theyve" to "they've",
            "theyll" to "they'll", "theyd" to "they'd",
            "thats" to "that's", "whats" to "what's",
            "shes" to "she's", "hes" to "he's"
        )
        contractions[lowered]?.let { corrected ->
            // 元の単語が大文字始まりだったら補正後も大文字始まりに（"Im" → "I'm" は I なのでそのまま）
            return if (word.isNotEmpty() && word[0].isUpperCase() && !corrected.startsWith("I"))
                corrected.replaceFirstChar { it.uppercaseChar() }
            else corrected
        }
        return word
    }

    private fun handleBackspace() {
        val consumed = engine.backspace()
        if (!consumed) {
            currentInputConnection?.deleteSurroundingText(1, 0)
        } else if (engine.state == InputState.IDLE) {
            // コンポジションが空になった → ICからも削除
            currentInputConnection?.setComposingText("", 1)
        }
    }

    private fun handleEnter() {
        when (engine.state) {
            InputState.COMPOSING -> {
                // commitText() はコンポジション領域をそのまま確定するので finishComposing 不要
                val text = engine.commitComposing()
                currentInputConnection?.commitText(text, 1)
            }
            InputState.CONVERTING -> {
                val selected = engine.selectCandidate(engine.selectedIndex)
                currentInputConnection?.commitText(selected, 1)
            }
            InputState.IDLE -> {
                currentInputConnection?.commitText("\n", 1)
            }
        }
    }

    private fun handleSpace() {
        when (engine.state) {
            InputState.COMPOSING -> {
                if (engine.mode == InputMode.ENGLISH) {
                    // 英語モード: 現在の単語を確定してスペース入力
                    commitEnglishComposing()
                    currentInputConnection?.commitText(" ", 1)
                } else {
                    engine.startConversion()
                }
            }
            InputState.CONVERTING -> engine.nextCandidate()
            InputState.IDLE -> {
                val sp = if (engine.mode == InputMode.JAPANESE) "　" else " "
                currentInputConnection?.commitText(sp, 1)
            }
        }
    }

    private fun handleConvert() {
        when (engine.state) {
            InputState.COMPOSING -> engine.startConversion()
            InputState.CONVERTING -> engine.nextCandidate()
            else -> {}
        }
    }

    private fun handleModifier() {
        if (engine.state == InputState.CONVERTING) engine.cancelConversion()
        engine.applyModifierToLast()
    }

    private fun handleSwitchMode() {
        commitEnglishComposing()
        engine.toggleMode()
        mainView.currentMode = engine.mode
    }

    private fun handleNumberMode() {
        commitEnglishComposing()
        engine.setMode(InputMode.ENGLISH)
        mainView.showNumberKeyboard()
    }

    private fun handleExitNumberMode() {
        val prevMode = mainView.exitNumberKeyboard()
        engine.setMode(prevMode)
        mainView.currentMode = prevMode
    }

    private fun handleCursorLeft() {
        if (engine.state != InputState.IDLE) return
        currentInputConnection?.apply {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }
    }

    private fun handleCursorRight() {
        if (engine.state != InputState.IDLE) return
        currentInputConnection?.apply {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
    }

    private fun handleClipboard() {
        val images = clipboardHelper.getImages()
        mainView.toggleClipboard(images)
    }

    /** 英語コンポジション中のテキストを確定する */
    private fun commitEnglishComposing() {
        if (engine.composing.isNotEmpty()) {
            val raw = engine.commitComposing()
            val corrected = autoCorrectEnglish(raw)
            currentInputConnection?.commitText(corrected, 1)
            triggerAiPrediction()
        }
    }

    private fun triggerAiPrediction() {
        if (engine.mode != InputMode.ENGLISH) return
        val key = settings.claudeApiKey
        if (key.isBlank()) return
        val context = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: return
        if (context.isBlank()) return
        aiPredictionJob?.cancel()
        aiPredictionJob = serviceScope.launch {
            val predictions = AIPrediction(key).predict(context)
            if (predictions.isNotEmpty() && engine.state == InputState.IDLE) {
                mainView.post {
                    isShowingAiPredictions = true
                    mainView.candidateView.candidates = predictions
                }
            }
        }
    }

    // ────────────── image commit ──────────────

    private fun commitImage(uri: Uri, mimeType: String) {
        val ic = currentInputConnection ?: return
        try {
            val contentInfo = InputContentInfo(
                uri,
                ClipDescription("image", arrayOf(mimeType)),
                null
            )
            ic.commitContent(
                contentInfo,
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (_: Exception) {
            currentInputConnection?.commitText(uri.toString(), 1)
        }
    }
}
