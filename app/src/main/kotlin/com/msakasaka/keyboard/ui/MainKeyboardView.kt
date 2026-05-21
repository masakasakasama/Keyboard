package com.msakasaka.keyboard.ui

import android.content.Context
import android.net.Uri
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
    val clipboardPanel: ClipboardPanel

    private var isClipboardShown = false

    var currentMode: InputMode = InputMode.JAPANESE
        set(value) {
            field = value
            flickKeyboard.currentMode = value
            flickKeyboard.visibility = if (value == InputMode.JAPANESE) View.VISIBLE else View.GONE
            qwertyKeyboard.visibility = if (value == InputMode.ENGLISH) View.VISIBLE else View.GONE
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.keyboard_main_view, this, true)
        candidateView = findViewById(R.id.candidate_view)
        flickKeyboard = findViewById(R.id.flick_keyboard)
        qwertyKeyboard = findViewById(R.id.qwerty_keyboard)
        clipboardPanel = findViewById(R.id.clipboard_panel)
        currentMode = InputMode.JAPANESE
    }

    fun applyKeyHeight(dp: Int) {
        flickKeyboard.keyHeightDp = dp
        qwertyKeyboard.keyHeightDp = dp
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
