package com.msakasaka.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.msakasaka.keyboard.R

class CandidateView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var candidates: List<String> = emptyList()
        set(value) {
            field = value
            candidateRects.clear()
            canvasScrollX = 0f
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var onCandidateClick: ((Int) -> Unit)? = null

    private val candidateRects = mutableListOf<RectF>()
    private var totalWidth = 0f
    private var canvasScrollX = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        color = ContextCompat.getColor(context, R.color.candidate_text)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_selected_bg)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_bg)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_divider)
        strokeWidth = 1f
    }

    private val padding = 28f
    private val cornerR = 6f

    private var touchStartX = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (candidates.isEmpty()) return

        candidateRects.clear()
        var x = padding - canvasScrollX
        val h = height.toFloat()
        val textY = (h + textPaint.textSize * 0.7f) / 2f

        candidates.forEachIndexed { i, text ->
            val tw = textPaint.measureText(text)
            val itemW = tw + padding * 2
            val rect = RectF(x, 2f, x + itemW, h - 2f)
            candidateRects.add(rect)

            if (i == selectedIndex) {
                canvas.drawRoundRect(rect, cornerR, cornerR, selectedPaint)
            }
            canvas.drawText(text, x + padding, textY, textPaint)

            x += itemW
            // divider
            if (i < candidates.size - 1) {
                canvas.drawLine(x, h * 0.2f, x, h * 0.8f, dividerPaint)
            }
        }
        totalWidth = x + padding + canvasScrollX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = touchStartX - event.x
                if (!isDragging && Math.abs(dx) > 8f) isDragging = true
                if (isDragging) {
                    canvasScrollX = (canvasScrollX + dx).coerceIn(0f, maxOf(0f, totalWidth - width))
                    touchStartX = event.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val tx = event.x + canvasScrollX
                    candidateRects.forEachIndexed { i, rect ->
                        if (tx >= rect.left && tx <= rect.right) {
                            onCandidateClick?.invoke(i)
                            return true
                        }
                    }
                }
            }
        }
        return true
    }
}
