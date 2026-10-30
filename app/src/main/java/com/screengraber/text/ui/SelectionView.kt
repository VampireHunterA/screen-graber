package com.screengraber.text.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.screengraber.text.R
import com.screengraber.text.capture.Timing
import kotlin.math.max
import kotlin.math.min

/**
 * Показывает захваченный кадр и позволяет выделить прямоугольную область.
 *
 * Кадр масштабируется целиком с сохранением пропорций. Вне выделения рисуется
 * полупрозрачное затемнение; внутри области изображение остаётся без изменений.
 * Ручки изменения размера и отдельный редактор не предусмотрены.
 */
class SelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val selection = RectF()

    private var hasSelection = false
    private var dragging = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var geometryLogged = false

    private val density = resources.displayMetrics.density
    private val minSelectionSize = 16f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = DIM_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * density, 0f, 1f * density, 0xCC000000.toInt())
    }

    /** Вызывается при появлении/исчезновении выделения. */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        hasSelection = false
        dragging = false
        geometryLogged = false
        selection.setEmpty()
        updateImageRect()
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    fun clearSelection() {
        hasSelection = false
        dragging = false
        selection.setEmpty()
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    /**
     * Выделение в координатах исходного Bitmap. Понадобится следующему этапу (OCR).
     */
    fun selectedBitmapRect(): Rect? {
        val source = bitmap ?: return null
        if (!hasSelection || selection.isEmpty) return null
        val scale = imageRect.width() / source.width
        if (scale <= 0f) return null

        val left = ((selection.left - imageRect.left) / scale).toInt()
        val top = ((selection.top - imageRect.top) / scale).toInt()
        val right = ((selection.right - imageRect.left) / scale).toInt()
        val bottom = ((selection.bottom - imageRect.top) / scale).toInt()

        return Rect(
            left.coerceIn(0, source.width),
            top.coerceIn(0, source.height),
            right.coerceIn(0, source.width),
            bottom.coerceIn(0, source.height)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometryLogged = false
        updateImageRect()
    }

    private fun updateImageRect() {
        val source = bitmap
        if (source == null || width == 0 || height == 0) {
            imageRect.setEmpty()
            return
        }
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        if (imageRect.isEmpty) return

        val oneToOne = imageRect.left == 0f && imageRect.top == 0f &&
            imageRect.width() == source.width.toFloat() &&
            imageRect.height() == source.height.toFloat()

        if (oneToOne) {
            // Полный снимок дисплея: рисуем 1:1 без ресемплинга.
            canvas.drawBitmap(source, 0f, 0f, null)
        } else {
            canvas.drawBitmap(source, null, imageRect, bitmapPaint)
        }

        if (!geometryLogged) {
            geometryLogged = true
            val scale = imageRect.width() / source.width
            Log.d(
                Timing.TAG,
                "view_draw at=${SystemClock.elapsedRealtime()} " +
                    "view=${width}x$height bitmap=${source.width}x${source.height} " +
                    "imageRect=[${imageRect.left},${imageRect.top}," +
                    "${imageRect.right},${imageRect.bottom}] scale=$scale oneToOne=$oneToOne"
            )
        }

        val showSelection = hasSelection || dragging
        if (showSelection && !selection.isEmpty) {
            drawDimOutsideSelection(canvas)
            canvas.drawRect(selection, borderPaint)
        } else {
            canvas.drawRect(imageRect, dimPaint)
        }

        val hintY = imageRect.top + 16f * density + hintPaint.textSize
        canvas.drawText(context.getString(R.string.selection_hint), width / 2f, hintY, hintPaint)
    }

    private fun drawDimOutsideSelection(canvas: Canvas) {
        // Четыре прямоугольника вокруг выделения: внутри изображение не затемняется.
        canvas.drawRect(0f, 0f, width.toFloat(), selection.top, dimPaint)
        canvas.drawRect(0f, selection.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selection.top, selection.left, selection.bottom, dimPaint)
        canvas.drawRect(selection.right, selection.top, width.toFloat(), selection.bottom, dimPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        bitmap ?: return false
        if (imageRect.isEmpty) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                dragging = true
                anchorX = clampX(event.x)
                anchorY = clampY(event.y)
                selection.set(anchorX, anchorY, anchorX, anchorY)
                if (hasSelection) {
                    hasSelection = false
                    onSelectionChanged?.invoke(false)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateSelection(anchorX, anchorY, clampX(event.x), clampY(event.y))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                updateSelection(anchorX, anchorY, clampX(event.x), clampY(event.y))
                val valid = selection.width() >= minSelectionSize &&
                    selection.height() >= minSelectionSize
                hasSelection = valid
                if (!valid) {
                    selection.setEmpty()
                }
                onSelectionChanged?.invoke(valid)
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (selection.width() < minSelectionSize || selection.height() < minSelectionSize) {
                    hasSelection = false
                    selection.setEmpty()
                    onSelectionChanged?.invoke(false)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelection(x1: Float, y1: Float, x2: Float, y2: Float) {
        selection.set(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    private fun clampX(value: Float) = value.coerceIn(imageRect.left, imageRect.right)

    private fun clampY(value: Float) = value.coerceIn(imageRect.top, imageRect.bottom)

    private companion object {
        const val DIM_COLOR = 0x78000000
    }
}
