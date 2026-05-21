package com.msakasaka.keyboard.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.engine.InputMode
import com.msakasaka.keyboard.util.ClipboardImage

class MainKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val candidateView: CandidateView
    val flickKeyboard: FlickKeyboardView
    val qwertyKeyboard: QwertyKeyboardView
    val numberKeyboard: NumberKeyboardView
    val clipboardPanel: ClipboardPanel

    private var isClipboardShown = false
    private var isNumberMode = false
    private var modeBeforeNumber = InputMode.JAPANESE

    var currentMode: InputMode = InputMode.JAPANESE
        set(value) {
            field = value
            flickKeyboard.currentMode = value
            if (!isNumberMode) updateKeyboardVisibility()
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.keyboard_main_view, this, true)
        candidateView = findViewById(R.id.candidate_view)
        flickKeyboard = findViewById(R.id.flick_keyboard)
        qwertyKeyboard = findViewById(R.id.qwerty_keyboard)
        numberKeyboard = findViewById(R.id.number_keyboard)
        clipboardPanel = findViewById(R.id.clipboard_panel)
        currentMode = InputMode.JAPANESE
    }

    private fun updateKeyboardVisibility() {
        flickKeyboard.visibility  = if (!isNumberMode && currentMode == InputMode.JAPANESE) View.VISIBLE else View.GONE
        qwertyKeyboard.visibility = if (!isNumberMode && currentMode == InputMode.ENGLISH)  View.VISIBLE else View.GONE
        numberKeyboard.visibility = if (isNumberMode) View.VISIBLE else View.GONE
    }

    fun showNumberKeyboard() {
        modeBeforeNumber = currentMode
        isNumberMode = true
        updateKeyboardVisibility()
    }

    fun exitNumberKeyboard(): InputMode {
        isNumberMode = false
        updateKeyboardVisibility()
        return modeBeforeNumber
    }

    fun applyKeyHeight(dp: Int) {
        flickKeyboard.keyHeightDp = dp
        qwertyKeyboard.keyHeightDp = dp
        numberKeyboard.keyHeightDp = dp
    }

    fun showClipboard(images: List<ClipboardImage>) {
        clipboardPanel.images = images
        if (!isClipboardShown) {
            clipboardPanel.visibility = View.VISIBLE
            isClipboardShown = true
        }
    }

    fun hideClipboard() {
        if (isClipboardShown) {
            clipboardPanel.visibility = View.GONE
            isClipboardShown = false
        }
    }

    fun toggleClipboard(images: List<ClipboardImage>) {
        if (isClipboardShown) hideClipboard() else showClipboard(images)
    }
}
