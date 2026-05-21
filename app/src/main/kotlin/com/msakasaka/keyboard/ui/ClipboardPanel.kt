package com.msakasaka.keyboard.ui

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.msakasaka.keyboard.R
import com.msakasaka.keyboard.util.ClipboardImage

class ClipboardPanel @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class ImageItem(val uri: Uri, val mimeType: String, @Volatile var bitmap: Bitmap? = null)

    var images: List<ClipboardImage> = emptyList()
        set(value) {
            field = value
            items = value.map { ImageItem(it.uri, it.mimeType) }
            loadThumbnailsAsync()
            invalidate()
        }

    var onImageSelected: ((Uri, String) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var items = listOf<ImageItem>()
    private var canvasScrollX = 0f
    private var touchStartX = 0f
    private var isDragging = false
    private val itemSize = 160f
    private val itemGap = 12f
    private val padding = 16f
    private val closeBtnSize = 40f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.clipboard_panel_bg)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clipboard_image_border)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val placeholderPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val closeBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 60, 60, 60)
    }
    private val closeBtnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun loadThumbnailsAsync() {
        val snapshot = items.toList()
        Thread {
            snapshot.forEach { item ->
                if (item.bitmap == null) {
                    try {
                        val stream = context.contentResolver.openInputStream(item.uri)
                        val raw = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        val size = itemSize.toInt()
                        item.bitmap = if (raw != null) Bitmap.createScaledBitmap(raw, size, size, true) else null
                        mainHandler.post { invalidate() }
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Close button top-right
        val closeBtnRight = width.toFloat() - 8f
        val closeBtnLeft = closeBtnRight - closeBtnSize
        val closeBtnRect = RectF(closeBtnLeft, 8f, closeBtnRight, 8f + closeBtnSize)
        canvas.drawRoundRect(closeBtnRect, 6f, 6f, closeBtnBg)
        canvas.drawText("×", closeBtnRect.centerX(), closeBtnRect.centerY() + closeBtnText.textSize * 0.35f, closeBtnText)

        if (items.isEmpty()) {
            canvas.drawText(
                "クリップボードに画像がありません",
                width / 2f, height / 2f + emptyTextPaint.textSize / 3f,
                emptyTextPaint
            )
            return
        }

        var x = padding - canvasScrollX
        val y = (height - itemSize) / 2f

        items.forEach { item ->
            val rect = RectF(x, y, x + itemSize, y + itemSize)
            if (item.bitmap != null) {
                canvas.drawBitmap(item.bitmap!!, null, rect, null)
            } else {
                canvas.drawRoundRect(rect, 8f, 8f, placeholderPaint)
            }
            canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
            x += itemSize + itemGap
        }
    }

    private fun isCloseBtn(x: Float, y: Float): Boolean {
        val closeBtnRight = width.toFloat() - 8f
        val closeBtnLeft = closeBtnRight - closeBtnSize
        return x >= closeBtnLeft && x <= closeBtnRight && y >= 8f && y <= 8f + closeBtnSize
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
                    val maxScroll = maxOf(0f, items.size * (itemSize + itemGap) - width + padding)
                    canvasScrollX = (canvasScrollX + dx).coerceIn(0f, maxScroll)
                    touchStartX = event.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (isCloseBtn(event.x, event.y)) {
                        onClose?.invoke()
                        return true
                    }
                    val tx = event.x + canvasScrollX
                    var x = padding
                    items.forEach { item ->
                        val y = (height - itemSize) / 2f
                        if (tx >= x && tx <= x + itemSize && event.y >= y && event.y <= y + itemSize) {
                            onImageSelected?.invoke(item.uri, item.mimeType)
                        }
                        x += itemSize + itemGap
                    }
                }
            }
        }
        return true
    }
}
