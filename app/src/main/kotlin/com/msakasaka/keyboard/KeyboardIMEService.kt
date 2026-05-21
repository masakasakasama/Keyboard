package com.msakasaka.keyboard

import android.content.SharedPreferences
import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.msakasaka.keyboard.engine.Dictionary
import com.msakasaka.keyboard.engine.InputMode
import com.msakasaka.keyboard.engine.InputState
import com.msakasaka.keyboard.engine.JapaneseInputEngine
import com.msakasaka.keyboard.settings.KeyboardSettings
import com.msakasaka.keyboard.ui.CandidateView
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

        engine.onStateChanged = { snapshot ->
            when (snapshot.state) {
                InputState.COMPOSING -> {
                    currentInputConnection?.setComposingText(snapshot.composing, 1)
                    mainView.candidateView.candidates = emptyList()
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

        mainView.candidateView.onCandidateClick = { index ->
            val selected = engine.selectCandidate(index)
            currentInputConnection?.apply {
                finishComposingText()
                commitText(selected, 1)
            }
            engine.reset()
            mainView.candidateView.candidates = emptyList()
        }

        val keyListener = object : KeyboardListener {
            override fun onChar(ch: String) = handleChar(ch)
            override fun onBackspace() = handleBackspace()
            override fun onEnter() = handleEnter()
            override fun onSpace() = handleSpace()
            override fun onConvert() = handleConvert()
            override fun onModifier() = handleModifier()
            override fun onSwitchMode() = handleSwitchMode()
            override fun onClipboardOpen() = handleClipboard()
        }
        mainView.flickKeyboard.listener = keyListener
        mainView.qwertyKeyboard.listener = keyListener

        mainView.clipboardPanel.onImageSelected = { uri, mimeType ->
            commitImage(uri, mimeType)
            mainView.hideClipboard()
        }

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
            currentInputConnection?.commitText(ch, 1)
            return
        }
        // 日本語モード: コンポジションに追加
        engine.appendChar(ch)
    }

    private fun handleBackspace() {
        val consumed = engine.backspace()
        if (!consumed) {
            // コンポジションが空→テキストフィールドから1文字削除
            currentInputConnection?.deleteSurroundingText(1, 0)
        } else if (engine.state == InputState.IDLE) {
            currentInputConnection?.finishComposingText()
        }
    }

    private fun handleEnter() {
        when (engine.state) {
            InputState.COMPOSING -> {
                val text = engine.commitComposing()
                currentInputConnection?.apply {
                    finishComposingText()
                    commitText(text, 1)
                }
            }
            InputState.CONVERTING -> {
                val selected = engine.selectCandidate(engine.selectedIndex)
                currentInputConnection?.apply {
                    finishComposingText()
                    commitText(selected, 1)
                }
                engine.reset()
            }
            InputState.IDLE -> {
                sendDefaultEditorAction(true)
            }
        }
        mainView.candidateView.candidates = emptyList()
    }

    private fun handleSpace() {
        when (engine.state) {
            InputState.COMPOSING -> {
                // スペースで変換開始
                engine.startConversion()
            }
            InputState.CONVERTING -> {
                engine.nextCandidate()
            }
            InputState.IDLE -> {
                if (engine.mode == InputMode.JAPANESE) {
                    currentInputConnection?.commitText("　", 1) // 全角スペース
                } else {
                    currentInputConnection?.commitText(" ", 1)
                }
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
        if (engine.state == InputState.CONVERTING) {
            engine.cancelConversion()
        }
        engine.applyModifierToLast()
    }

    private fun handleSwitchMode() {
        // コンポジション中は先にコミット
        if (engine.state != InputState.IDLE) {
            val text = engine.commitComposing()
            currentInputConnection?.apply {
                finishComposingText()
                commitText(text, 1)
            }
            engine.reset()
        }
        engine.toggleMode()
        mainView.currentMode = engine.mode
    }

    private fun handleClipboard() {
        val images = clipboardHelper.getImages()
        mainView.toggleClipboard(images)
    }

    // ────────────── image commit ──────────────

    private fun commitImage(uri: Uri, mimeType: String) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo ?: return

        try {
            val contentInfo = InputContentInfoCompat(
                uri,
                ClipDescription("image", arrayOf(mimeType)),
                null
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else {
                0
            }
            InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, flags, null)
        } catch (_: Exception) {
            // フォールバック: URI文字列をテキストとして送る
            currentInputConnection?.commitText(uri.toString(), 1)
        }
    }

    // ────────────── 全角記号 ──────────────

    override fun onText(text: CharSequence?) {
        currentInputConnection?.commitText(text, 1)
    }
}
