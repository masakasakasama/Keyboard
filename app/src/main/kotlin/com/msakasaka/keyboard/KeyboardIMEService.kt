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
import com.msakasaka.keyboard.util.ClipboardHelper

class KeyboardIMEService : InputMethodService() {

    private lateinit var mainView: MainKeyboardView
    private lateinit var engine: JapaneseInputEngine
    private lateinit var dictionary: Dictionary
    private lateinit var settings: KeyboardSettings
    private lateinit var clipboardHelper: ClipboardHelper

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
    }

    override fun onDestroy() {
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
                    currentInputConnection?.setComposingText(snapshot.composing, 1)
                    mainView.candidateView.candidates = snapshot.candidates
                }
                InputState.CONVERTING -> {
                    currentInputConnection?.setComposingText(snapshot.composing, 1)
                    mainView.candidateView.candidates = snapshot.candidates
                    mainView.candidateView.selectedIndex = snapshot.selectedCandidateIndex
                }
                InputState.IDLE -> {
                    mainView.candidateView.candidates = emptyList()
                }
            }
        }

        // commitText() replaces any current composing region — no finishComposingText() needed
        mainView.candidateView.onCandidateClick = { index ->
            val selected = engine.selectCandidate(index)
            currentInputConnection?.commitText(selected, 1)
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
                engine.appendChar(ch.lowercase())
            } else {
                // 記号・数字はコンポジション確定→記号入力
                commitEnglishComposing()
                currentInputConnection?.commitText(ch, 1)
            }
            return
        }
        engine.appendChar(ch)
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
                sendDefaultEditorAction(true)
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
            val text = engine.commitComposing()
            currentInputConnection?.commitText(text, 1)
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
