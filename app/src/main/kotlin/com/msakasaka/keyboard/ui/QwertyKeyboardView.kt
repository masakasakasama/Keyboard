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

private data class QwertyKey(
    val label: String,
    val output: String = "",
    val widthWeight: Float = 1f,
    val isSpecial: Boolean = false
)

class QwertyKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: KeyboardListener? = null
    var keyHeightDp: Int = 56
        set(value) { field = value; requestLayout() }

    var widthScale: Int = 100
        set(value) { field = value; requestLayout() }

    private var isShifted = false

    private val rows = listOf(
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("⇧","z","x","c","v","b","n","m","⌫"),
        listOf("JP","123",",","SPC",".",  "↵")
    )

    private val ROW_COUNT = rows.size
    private val gap = 3f
    private var kh = 0f

    private val normalBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_normal_bg)
    }
    private val specialBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_special_bg)
    }
    private val shiftActiveBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.colorPrimary)
    }
    private val pressedBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_pressed_bg)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text_primary)
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var pressedKey: Pair<Int, Int>? = null

    private val bsHandler = Handler(Looper.getMainLooper())
    private val bsRunnable = object : Runnable {
        override fun run() { listener?.onBackspace(); bsHandler.postDelayed(this, 50) }
    }

    // precomputed key rects per row: [row][col] -> RectF
    private val keyRects = mutableListOf<List<RectF>>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        kh = keyHeightDp * resources.displayMetrics.density
        val totalH = (ROW_COUNT * kh + (ROW_COUNT + 1) * gap).toInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        computeRects(w)
    }

    private fun computeRects(totalW: Int) {
        keyRects.clear()
        val scaledW = (totalW * widthScale / 100f).toInt()
        val leftPad = (totalW - scaledW) / 2f
        rows.forEachIndexed { rowIdx, row ->
            val y = gap + rowIdx * (kh + gap)
            val rects = mutableListOf<RectF>()

            if (rowIdx == 3) {
                val weights = listOf(1.5f, 1.5f, 0.8f, 3f, 0.8f, 1.5f)
                val totalWeight = weights.sum()
                val usable = scaledW - gap * (weights.size + 1)
                val unit = usable / totalWeight
                var x = leftPad + gap
                weights.forEach { w ->
                    val kw = unit * w
                    rects.add(RectF(x, y, x + kw, y + kh))
                    x += kw + gap
                }
            } else {
                val cols = row.size
                val kw = (scaledW - gap * (cols + 1)) / cols
                var x = leftPad + gap
                repeat(cols) {
                    rects.add(RectF(x, y, x + kw, y + kh))
                    x += kw + gap
                }
            }
            keyRects.add(rects)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (keyRects.isEmpty()) computeRects(width)
        rows.forEachIndexed { rowIdx, row ->
            row.forEachIndexed { colIdx, key ->
                if (colIdx >= keyRects[rowIdx].size) return@forEachIndexed
                val rect = keyRects[rowIdx][colIdx]
                val isP = pressedKey == Pair(rowIdx, colIdx)
                val isSpec = isSpecialKey(key)
                val bg = when {
                    isP -> pressedBg
                    key == "⇧" && isShifted -> shiftActiveBg
                    isSpec -> specialBg
                    else -> normalBg
                }
                canvas.drawRoundRect(rect, 8f, 8f, bg)
                canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

                textPaint.textSize = kh * 0.36f
                val displayKey = when (key) {
                    "SPC" -> "空白"
                    "⇧" -> if (isShifted) "⬆" else "⇧"
                    else -> if (!isSpec && isShifted) key.uppercase() else key
                }
                val cx = rect.centerX()
                val cy = rect.centerY() + textPaint.textSize * 0.35f
                canvas.drawText(displayKey, cx, cy, textPaint)
            }
        }
    }

    private fun isSpecialKey(k: String) = k in setOf("⇧", "⌫", "JP", "↵", "123", "SPC")

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = hitTest(event.x, event.y)
                pressedKey = hit
                if (hit != null && rows[hit.first].getOrNull(hit.second) == "⌫") {
                    bsHandler.postDelayed(bsRunnable, 500)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                bsHandler.removeCallbacks(bsRunnable)
                val hit = hitTest(event.x, event.y)
                if (hit != null && hit == pressedKey) {
                    handleKey(hit.first, hit.second)
                }
                pressedKey = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                bsHandler.removeCallbacks(bsRunnable)
                pressedKey = null
                invalidate()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Pair<Int, Int>? {
        keyRects.forEachIndexed { rowIdx, rects ->
            rects.forEachIndexed { colIdx, rect ->
                if (rect.contains(x, y)) return Pair(rowIdx, colIdx)
            }
        }
        return null
    }

    private fun handleKey(row: Int, col: Int) {
        val key = rows[row].getOrNull(col) ?: return
        when (key) {
            "⌫" -> listener?.onBackspace()
            "↵" -> listener?.onEnter()
            "⇧" -> { isShifted = !isShifted; invalidate() }
            "JP" -> listener?.onSwitchMode()
            "SPC" -> listener?.onSpace()
            "123" -> listener?.onNumberMode()
            else -> {
                val out = if (isShifted) key.uppercase() else key
                listener?.onChar(out)
                if (isShifted) { isShifted = false; invalidate() }
            }
        }
    }
}
