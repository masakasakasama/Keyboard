package com.msakasaka.keyboard.ui

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.engine.FlickCharTable
import com.msakasaka.keyboard.engine.InputMode
import kotlin.math.abs
import kotlin.math.sqrt

interface KeyboardListener {
    fun onChar(ch: String)
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onConvert()
    fun onModifier()
    fun onSwitchMode()
    fun onNumberMode() {}
    fun onExitNumberMode() {}
    fun onClipboardOpen()
    fun onCursorLeft() {}
    fun onCursorRight() {}
}

class FlickKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: KeyboardListener? = null
    var currentMode: InputMode = InputMode.JAPANESE
        set(value) { field = value; invalidate() }

    var keyHeightDp: Int = 56
        set(value) { field = value; requestLayout() }

    var widthScale: Int = 100
        set(value) { field = value; requestLayout() }

    private val TOTAL_COLS = 5
    private val CHAR_ROWS = 4
    private val gap = 3f
    private var kw = 0f
    private var kh = 0f
    private var numStripH = 0f
    private var numCellW = 0f
    private var leftPad = 0f
    private var scaledW = 0f

    // Middle columns (1-3): character keys
    private val charKeys = listOf(
        listOf("あ", "か", "さ"),
        listOf("た", "な", "は"),
        listOf("ま", "や", "ら"),
        listOf("小゛", "わ", "。")
    )

    // [col0 label, col4 label] for each row
    private val funcKeys = listOf(
        listOf("clip", "⌫"),
        listOf("＜", "＞"),
        listOf("1&+", "空白"),
        listOf("abc", "↵")
    )

    private val numbers = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    // pressedRow: -2=no press, -1=number strip, 0..3=char rows
    private var pressedRow = -2
    private var pressedCol = -1
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var flickCommitted = false

    private val FLICK_THRESHOLD_DP = 20f
    private val flickThreshold get() = FLICK_THRESHOLD_DP * resources.displayMetrics.density

    private val bsHandler = Handler(Looper.getMainLooper())
    private val bsRunnable = object : Runnable {
        override fun run() { listener?.onBackspace(); bsHandler.postDelayed(this, 50) }
    }

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
        numStripH = kh * 0.55f
        scaledW = w * widthScale / 100f
        leftPad = (w - scaledW) / 2f
        kw = (scaledW - gap * (TOTAL_COLS + 1)) / TOTAL_COLS
        numCellW = scaledW / 10f
        val totalH = (numStripH + gap * (CHAR_ROWS + 1) + CHAR_ROWS * kh).toInt()
        setMeasuredDimension(w, totalH)
    }

    private fun stripCellLeft(i: Int) = leftPad + i * numCellW
    private fun colLeft(col: Int) = leftPad + gap + col * (kw + gap)
    private fun rowTop(row: Int) = numStripH + gap + row * (kh + gap)

    override fun onDraw(canvas: Canvas) {
        drawNumberStrip(canvas)
        for (row in 0 until CHAR_ROWS) {
            for (col in 0 until TOTAL_COLS) {
                drawKey(canvas, row, col)
            }
        }
    }

    private fun drawNumberStrip(canvas: Canvas) {
        numbers.forEachIndexed { i, num ->
            val x = stripCellLeft(i)
            val isPressed = pressedRow == -1 && pressedCol == i
            val rect = RectF(x + 1f, 1f, x + numCellW - 1f, numStripH - 1f)
            canvas.drawRoundRect(rect, 6f, 6f, if (isPressed) pressedBg else specialBg)
            canvas.drawRoundRect(rect, 6f, 6f, borderPaint)
            primaryText.textSize = numStripH * 0.48f
            canvas.drawText(num, x + numCellW / 2f, numStripH * 0.68f, primaryText)
        }
    }

    private fun getKeyLabel(row: Int, col: Int): String = when (col) {
        0 -> funcKeys[row][0]
        4 -> funcKeys[row][1]
        else -> charKeys[row][col - 1]
    }

    private fun isSpecialBg(row: Int, col: Int): Boolean {
        val label = getKeyLabel(row, col)
        return col == 0 || label in setOf("⌫", "↵", "小゛")
    }

    private fun drawKey(canvas: Canvas, row: Int, col: Int) {
        val x = colLeft(col)
        val y = rowTop(row)
        val label = getKeyLabel(row, col)
        val isPressed = pressedRow == row && pressedCol == col

        val bg = if (isPressed) pressedBg else if (isSpecialBg(row, col)) specialBg else normalBg
        val rect = RectF(x, y, x + kw, y + kh)
        canvas.drawRoundRect(rect, 8f, 8f, bg)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val cx = x + kw / 2f
        val cy = y + kh / 2f
        primaryText.textSize = kh * 0.36f
        secondaryText.textSize = kh * 0.20f

        when {
            col == 0 || col == 4 || label == "小゛" -> {
                primaryText.textSize = when (label) {
                    "1&+", "abc", "clip" -> kh * 0.26f
                    "空白" -> kh * 0.28f
                    else -> kh * 0.34f
                }
                val display = if (label == "↵") "確定" else label
                canvas.drawText(display, cx, cy + primaryText.textSize * 0.35f, primaryText)
            }
            else -> {
                canvas.drawText(label, cx, cy + primaryText.textSize * 0.35f, primaryText)
                FlickCharTable.JA_KEYS[label]?.let { f ->
                    if (f.up.isNotEmpty())    canvas.drawText(f.up,    cx,              y + kh * 0.22f, secondaryText)
                    if (f.right.isNotEmpty()) canvas.drawText(f.right, x + kw * 0.82f, cy + secondaryText.textSize * 0.35f, secondaryText)
                    if (f.down.isNotEmpty())  canvas.drawText(f.down,  cx,              y + kh * 0.88f, secondaryText)
                    if (f.left.isNotEmpty())  canvas.drawText(f.left,  x + kw * 0.18f, cy + secondaryText.textSize * 0.35f, secondaryText)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> handleMove(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleUp(event.x, event.y)
        }
        return true
    }

    private fun isBackspaceKey(row: Int, col: Int) = col == 4 && row == 0

    private fun handleDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y
        flickCommitted = false
        if (y < numStripH) {
            pressedRow = -1
            pressedCol = ((x - leftPad) / numCellW).toInt().coerceIn(0, 9)
        } else {
            val r = rowAt(y)
            val c = colAt(x)
            if (r >= 0 && c >= 0) {
                pressedRow = r
                pressedCol = c
                if (isBackspaceKey(r, c)) bsHandler.postDelayed(bsRunnable, 500)
            } else {
                pressedRow = -2
                pressedCol = -1
            }
        }
        invalidate()
    }

    private fun handleMove(x: Float, y: Float) {
        if (pressedRow < 0 || flickCommitted) return
        if (pressedCol !in 1..3) return
        val dx = x - touchStartX
        val dy = y - touchStartY
        if (sqrt(dx * dx + dy * dy) >= flickThreshold) {
            commitFlick(pressedRow, pressedCol, dx, dy)
            flickCommitted = true
            pressedRow = -2
            pressedCol = -1
            invalidate()
        }
    }

    private fun handleUp(x: Float, y: Float) {
        bsHandler.removeCallbacks(bsRunnable)
        if (!flickCommitted) {
            when {
                pressedRow == -1 && pressedCol in 0..9 -> listener?.onChar(numbers[pressedCol])
                pressedRow >= 0 && pressedCol >= 0 -> commitTap(pressedRow, pressedCol)
            }
        }
        pressedRow = -2
        pressedCol = -1
        flickCommitted = false
        invalidate()
    }

    private fun commitTap(row: Int, col: Int) {
        when (val label = getKeyLabel(row, col)) {
            "⌫"   -> listener?.onBackspace()
            "↵"   -> listener?.onEnter()
            "空白" -> listener?.onSpace()
            "clip" -> listener?.onClipboardOpen()
            "＜"   -> listener?.onCursorLeft()
            "＞"   -> listener?.onCursorRight()
            "1&+" -> listener?.onNumberMode()
            "abc"  -> listener?.onSwitchMode()
            "小゛" -> listener?.onModifier()
            else -> {
                val ch = FlickCharTable.JA_KEYS[label]?.center ?: label
                listener?.onChar(ch)
            }
        }
    }

    private fun commitFlick(row: Int, col: Int, dx: Float, dy: Float) {
        val label = charKeys[row][col - 1]
        val flick = FlickCharTable.JA_KEYS[label] ?: run { commitTap(row, col); return }
        val ch = if (abs(dx) > abs(dy)) {
            if (dx > 0) flick.right.ifEmpty { flick.center }
            else flick.left.ifEmpty { flick.center }
        } else {
            if (dy < 0) flick.up.ifEmpty { flick.center }
            else flick.down.ifEmpty { flick.center }
        }
        listener?.onChar(ch)
    }

    private fun colAt(x: Float): Int {
        for (c in 0 until TOTAL_COLS) {
            val left = colLeft(c)
            if (x >= left && x < left + kw) return c
        }
        return -1
    }

    private fun rowAt(y: Float): Int {
        for (r in 0 until CHAR_ROWS) {
            val top = rowTop(r)
            if (y >= top && y < top + kh) return r
        }
        return -1
    }
}
