package com.msakasaka.keyboard.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.msakasaka.keyboard.R
import kotlin.math.abs
import kotlin.math.sqrt

class NumberKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: KeyboardListener? = null
    var keyHeightDp: Int = 56
        set(value) { field = value; requestLayout() }

    var widthScale: Int = 100
        set(value) { field = value; requestLayout() }

    private val ROWS = 4
    private val COLS = 5
    private val gap = 3f
    private var kw = 0f
    private var kh = 0f
    private var leftPad = 0f

    // Layout matches image 2: left col = func, cols 1-3 = numpad, right col = func
    private val keys = listOf(
        listOf("記号", "1", "2", "3", "⌫"),
        listOf(":", "4", "5", "6", "-"),
        listOf("↩", "7", "8", "9", "空白"),
        listOf("あA", "@", "0", "#", "↵")
    )

    private data class FlickEntry(
        val center: String,
        val up: String = "",
        val right: String = "",
        val down: String = "",
        val left: String = ""
    )

    private val flickMap = mapOf(
        "@" to FlickEntry("@", "&", ".", "", ""),
        "0" to FlickEntry("0", "+", "", "%", "")
    )

    private var pressedRow = -1
    private var pressedCol = -1
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var flickCommitted = false

    private val FLICK_THRESHOLD_DP = 20f
    private val flickThreshold get() = FLICK_THRESHOLD_DP * resources.displayMetrics.density

    private val normalBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_normal_bg)
    }
    private val specialBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_special_bg)
    }
    private val pressedBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_pressed_bg)
    }
    private val primaryText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text_primary)
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val secondaryText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text_secondary)
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        kh = keyHeightDp * resources.displayMetrics.density
        val scaledW = w * widthScale / 100f
        leftPad = (w - scaledW) / 2f
        kw = (scaledW - gap * (COLS + 1)) / COLS
        val totalH = (ROWS * kh + (ROWS + 1) * gap).toInt()
        setMeasuredDimension(w, totalH)
    }

    private fun keyLeft(col: Int) = leftPad + gap + col * (kw + gap)
    private fun keyTop(row: Int) = gap + row * (kh + gap)

    private fun isSpecialBg(row: Int, col: Int): Boolean {
        val label = keys[row][col]
        return col == 0 || label in setOf("⌫", "↵", "-", "空白", ":")
    }

    override fun onDraw(canvas: Canvas) {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                drawKey(canvas, row, col)
            }
        }
    }

    private fun drawKey(canvas: Canvas, row: Int, col: Int) {
        val label = keys[row][col]
        val x = keyLeft(col)
        val y = keyTop(row)
        val isPressed = pressedRow == row && pressedCol == col

        val bg = if (isPressed) pressedBg else if (isSpecialBg(row, col)) specialBg else normalBg
        val rect = RectF(x, y, x + kw, y + kh)
        canvas.drawRoundRect(rect, 8f, 8f, bg)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val cx = x + kw / 2f
        val cy = y + kh / 2f
        primaryText.textSize = kh * 0.36f
        secondaryText.textSize = kh * 0.20f

        val display = when (label) {
            "↵" -> "確定"
            "空白" -> "空白"
            else -> label
        }
        canvas.drawText(display, cx, cy + primaryText.textSize * 0.35f, primaryText)

        flickMap[label]?.let { f ->
            if (f.up.isNotEmpty())    canvas.drawText(f.up,    cx,              y + kh * 0.22f, secondaryText)
            if (f.right.isNotEmpty()) canvas.drawText(f.right, x + kw * 0.82f, cy + secondaryText.textSize * 0.35f, secondaryText)
            if (f.down.isNotEmpty())  canvas.drawText(f.down,  cx,              y + kh * 0.88f, secondaryText)
            if (f.left.isNotEmpty())  canvas.drawText(f.left,  x + kw * 0.18f, cy + secondaryText.textSize * 0.35f, secondaryText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y) ?: return true
                pressedRow = hit.first
                pressedCol = hit.second
                touchStartX = event.x
                touchStartY = event.y
                flickCommitted = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedRow >= 0 && !flickCommitted) {
                    val label = keys[pressedRow][pressedCol]
                    val flick = flickMap[label] ?: return true
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (sqrt(dx * dx + dy * dy) >= flickThreshold) {
                        val ch = if (abs(dx) > abs(dy)) {
                            if (dx > 0) flick.right.ifEmpty { flick.center }
                            else flick.left.ifEmpty { flick.center }
                        } else {
                            if (dy < 0) flick.up.ifEmpty { flick.center }
                            else flick.down.ifEmpty { flick.center }
                        }
                        listener?.onChar(ch)
                        flickCommitted = true
                        pressedRow = -1
                        pressedCol = -1
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!flickCommitted && pressedRow >= 0) handleTap(pressedRow, pressedCol)
                pressedRow = -1
                pressedCol = -1
                flickCommitted = false
                invalidate()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Pair<Int, Int>? {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (x >= keyLeft(c) && x < keyLeft(c) + kw &&
                    y >= keyTop(r) && y < keyTop(r) + kh) return r to c
            }
        }
        return null
    }

    private fun handleTap(row: Int, col: Int) {
        when (val label = keys[row][col]) {
            "⌫"   -> listener?.onBackspace()
            "↵"   -> listener?.onEnter()
            "空白" -> listener?.onSpace()
            "↩"   -> listener?.onBackspace()
            "あA"  -> listener?.onExitNumberMode()
            "記号" -> { /* TODO: symbols page */ }
            else  -> listener?.onChar(label)
        }
    }
}
