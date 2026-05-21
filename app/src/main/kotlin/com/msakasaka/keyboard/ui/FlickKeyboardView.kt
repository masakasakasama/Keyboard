package com.msakasaka.keyboard.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.engine.FlickCharTable
import com.msakasaka.keyboard.engine.InputMode
import kotlin.math.abs
import kotlin.math.sqrt

// キーボードのアクション通知
interface KeyboardListener {
    fun onChar(ch: String)
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onConvert()
    fun onModifier()       // 小/゛
    fun onSwitchMode()     // JP↔EN
    fun onClipboardOpen()
}

private enum class KeyType {
    CHAR, BACKSPACE, ENTER, SPACE, MODIFIER, SWITCH_MODE, CLIPBOARD
}

private data class KeyDef(
    val label: String,
    val type: KeyType,
    val charKey: String = "",   // FlickCharTable のキー
    val row: Int = 0,
    val col: Int = 0
)

class FlickKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: KeyboardListener? = null
    var currentMode: InputMode = InputMode.JAPANESE
        set(value) { field = value; invalidate() }

    // Settings から注入される
    var keyHeightDp: Int = 56
        set(value) { field = value; requestLayout() }

    // ────────────── layout constants ──────────────
    private val COL = 3
    private val CHAR_ROWS = 4    // あ〜わ行 + 小゛キー
    private val FUNC_ROW = 1

    // ────────────── paint ──────────────
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

    // ────────────── key grid ──────────────
    // row0〜3: char keys, row4: function keys
    private val charKeys = listOf(
        listOf("あ", "か", "さ"),
        listOf("た", "な", "は"),
        listOf("ま", "や", "ら"),
        listOf("小゛", "わ", "⌫")
    )

    // ────────────── touch state ──────────────
    private var pressedRow = -1
    private var pressedCol = -1
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var flickCommitted = false

    private var kw = 0f  // key width
    private var kh = 0f  // key height
    private val gap = 3f

    private val FLICK_THRESHOLD_DP = 20f
    private val flickThreshold get() = FLICK_THRESHOLD_DP * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        kh = keyHeightDp * resources.displayMetrics.density
        kw = (w - gap * (COL + 1)) / COL
        val totalH = ((CHAR_ROWS + FUNC_ROW) * kh + (CHAR_ROWS + FUNC_ROW + 1) * gap).toInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onDraw(canvas: Canvas) {
        // Draw all keys
        for (row in 0 until CHAR_ROWS) {
            for (col in 0 until COL) {
                drawCharKey(canvas, row, col)
            }
        }
        drawFuncRow(canvas)
    }

    private fun keyLeft(col: Int) = gap + col * (kw + gap)
    private fun keyTop(row: Int) = gap + row * (kh + gap)

    private fun drawCharKey(canvas: Canvas, row: Int, col: Int) {
        val label = charKeys[row][col]
        val x = keyLeft(col)
        val y = keyTop(row)
        val isPressed = pressedRow == row && pressedCol == col
        val isSpecial = label == "⌫" || label == "小゛"

        val bg = when {
            isPressed -> pressedBg
            isSpecial -> specialBg
            else -> normalBg
        }
        val rect = RectF(x, y, x + kw, y + kh)
        canvas.drawRoundRect(rect, 8f, 8f, bg)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        primaryText.textSize = kh * 0.38f
        secondaryText.textSize = kh * 0.20f

        val cx = x + kw / 2f
        val cy = y + kh / 2f

        when (label) {
            "⌫" -> {
                primaryText.textSize = kh * 0.35f
                canvas.drawText("⌫", cx, cy + primaryText.textSize * 0.35f, primaryText)
            }
            "小゛" -> {
                primaryText.textSize = kh * 0.30f
                canvas.drawText("小/゛", cx, cy + primaryText.textSize * 0.35f, primaryText)
            }
            else -> {
                val flick = FlickCharTable.JA_KEYS[label]
                // 中央
                canvas.drawText(label, cx, cy + primaryText.textSize * 0.35f, primaryText)
                // サブ文字（小さく四隅）
                flick?.let {
                    val sub = secondaryText
                    if (it.up.isNotEmpty())    canvas.drawText(it.up,    cx,          y + kh * 0.22f, sub)
                    if (it.right.isNotEmpty()) canvas.drawText(it.right, x + kw * 0.82f, cy + sub.textSize * 0.35f, sub)
                    if (it.down.isNotEmpty())  canvas.drawText(it.down,  cx,          y + kh * 0.88f, sub)
                    if (it.left.isNotEmpty())  canvas.drawText(it.left,  x + kw * 0.18f, cy + sub.textSize * 0.35f, sub)
                }
            }
        }
    }

    private fun drawFuncRow(canvas: Canvas) {
        val row = CHAR_ROWS
        val y = keyTop(row)

        // 左: JP/EN切り替え
        run {
            val x = keyLeft(0)
            val rect = RectF(x, y, x + kw, y + kh)
            canvas.drawRoundRect(rect, 8f, 8f, specialBg)
            canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
            primaryText.textSize = kh * 0.28f
            val label = if (currentMode == InputMode.JAPANESE) "JP\nEN" else "EN\nJP"
            val lines = label.split("\n")
            canvas.drawText(lines[0], x + kw / 2f, y + kh * 0.40f, primaryText)
            secondaryText.textSize = kh * 0.22f
            canvas.drawText(lines[1], x + kw / 2f, y + kh * 0.70f, secondaryText)
        }

        // 中央: スペース
        run {
            val x = keyLeft(1)
            val rect = RectF(x, y, x + kw, y + kh)
            canvas.drawRoundRect(rect, 8f, 8f, normalBg)
            canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
            primaryText.textSize = kh * 0.28f
            canvas.drawText("空白", x + kw / 2f, y + kh * 0.60f, primaryText)
        }

        // 右: Enter
        run {
            val x = keyLeft(2)
            val rect = RectF(x, y, x + kw, y + kh)
            canvas.drawRoundRect(rect, 8f, 8f, specialBg)
            canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
            primaryText.textSize = kh * 0.28f
            canvas.drawText("確定", x + kw / 2f, y + kh * 0.60f, primaryText)
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

    private fun handleDown(x: Float, y: Float) {
        touchStartX = x
        touchStartY = y
        flickCommitted = false

        val col = colAt(x)
        val row = rowAt(y)
        if (row < 0 || col < 0) return

        pressedRow = row
        pressedCol = col
        invalidate()
    }

    private fun handleMove(x: Float, y: Float) {
        if (pressedRow < 0 || flickCommitted) return
        val dx = x - touchStartX
        val dy = y - touchStartY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist >= flickThreshold) {
            commitFlick(pressedRow, pressedCol, dx, dy)
            flickCommitted = true
            pressedRow = -1
            pressedCol = -1
            invalidate()
        }
    }

    private fun handleUp(x: Float, y: Float) {
        if (!flickCommitted && pressedRow >= 0) {
            commitTap(pressedRow, pressedCol)
        }
        pressedRow = -1
        pressedCol = -1
        flickCommitted = false
        invalidate()
    }

    private fun commitTap(row: Int, col: Int) {
        if (row == CHAR_ROWS) {
            when (col) {
                0 -> listener?.onSwitchMode()
                1 -> listener?.onSpace()
                2 -> listener?.onEnter()
            }
            return
        }
        val label = charKeys[row][col]
        when (label) {
            "⌫" -> listener?.onBackspace()
            "小゛" -> listener?.onModifier()
            else -> {
                val ch = FlickCharTable.JA_KEYS[label]?.center ?: label
                listener?.onChar(ch)
            }
        }
    }

    private fun commitFlick(row: Int, col: Int, dx: Float, dy: Float) {
        if (row == CHAR_ROWS) {
            // ファンクション行はフリックしない
            commitTap(row, col)
            return
        }
        val label = charKeys[row][col]
        if (label == "⌫" || label == "小゛") {
            commitTap(row, col)
            return
        }

        val flick = FlickCharTable.JA_KEYS[label] ?: return
        val ch = flickDirection(dx, dy, flick)
        listener?.onChar(ch)
    }

    private fun flickDirection(dx: Float, dy: Float, flick: com.msakasaka.keyboard.engine.FlickChars): String {
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) flick.right.ifEmpty { flick.center }
            else flick.left.ifEmpty { flick.center }
        } else {
            if (dy < 0) flick.up.ifEmpty { flick.center }
            else flick.down.ifEmpty { flick.center }
        }
    }

    private fun colAt(x: Float): Int {
        for (c in 0 until COL) {
            val left = keyLeft(c)
            if (x >= left && x < left + kw) return c
        }
        return -1
    }

    private fun rowAt(y: Float): Int {
        val totalRows = CHAR_ROWS + FUNC_ROW
        for (r in 0 until totalRows) {
            val top = keyTop(r)
            if (y >= top && y < top + kh) return r
        }
        return -1
    }
}
