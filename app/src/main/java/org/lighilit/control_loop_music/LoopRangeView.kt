//package com.example.controlloopmusic
package org.lighilit.control_loop_music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LoopRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Listener {
        fun onRangeChanged(startMs: Long, endMs: Long)
        fun onPositionRequested(positionMs: Long)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var positionMs = 0L
    private var waveform = FloatArray(0)
    private var dragging = DRAG_NONE
    private var listener: Listener? = null

    init {
        minimumHeight = dp(72f).roundToInt()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs.coerceAtLeast(0)
        startMs = 0
        endMs = this.durationMs
        positionMs = 0
        invalidate()
        emitRange()
    }

    fun setPosition(positionMs: Long) {
        this.positionMs = positionMs.coerceIn(0, durationMs)
        invalidate()
    }

    fun setRange(startMs: Long, endMs: Long) {
        val minGap = min(MIN_GAP_MS, durationMs)
        val clampedStart = startMs.coerceIn(0, durationMs)
        val clampedEnd = endMs.coerceIn(0, durationMs)
        require(clampedEnd - clampedStart >= minGap) { "Loop range is too short" }
        this.startMs = clampedStart
        this.endMs = clampedEnd
        positionMs = positionMs.coerceIn(clampedStart, clampedEnd)
        invalidate()
        emitRange()
    }

    fun getStartMs(): Long = startMs

    fun getEndMs(): Long = endMs

    fun setWaveform(waveform: FloatArray?) {
        this.waveform = waveform ?: FloatArray(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelSpace = dp(24f)
        val pad = dp(18f)
        val left = pad
        val right = width - pad
        val trackTop = labelSpace
        val trackBottom = height - dp(8f)
        val trackHeight = max(dp(28f), trackBottom - trackTop)
        val centerY = trackTop + trackHeight / 2f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(238, 241, 245)
        rect.set(left, trackTop, right, trackTop + trackHeight)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)
        drawWaveform(canvas, left, right, centerY, trackHeight)

        val startX = msToX(startMs, left, right)
        val endX = msToX(endMs, left, right)
        paint.color = Color.argb(56, 47, 128, 237)
        rect.set(startX, trackTop, endX, trackTop + trackHeight)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(startX, centerY, dp(10f), paint)
        canvas.drawCircle(endX, centerY, dp(10f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.rgb(29, 79, 145)
        canvas.drawCircle(startX, centerY, dp(10f), paint)
        canvas.drawCircle(endX, centerY, dp(10f), paint)

        val positionX = msToX(positionMs, left, right)
        paint.color = Color.rgb(214, 40, 40)
        paint.strokeWidth = dp(3f)
        canvas.drawLine(positionX, trackTop - dp(2f), positionX, trackTop + trackHeight + dp(2f), paint)

        paint.style = Paint.Style.FILL
        paint.textSize = dp(12f)
        paint.color = Color.rgb(31, 41, 55)
        drawCenteredText(canvas, "%.2fs".format(startMs / 1000f), startX, dp(15f))
        drawCenteredText(canvas, "%.2fs".format(endMs / 1000f), endX, dp(15f))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        val left = dp(18f)
        val right = width - dp(18f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = nearestHandle(event.x, left, right)
                parent.requestDisallowInterceptTouchEvent(true)
                moveHandle(event.x, left, right)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveHandle(event.x, left, right)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = DRAG_NONE
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun drawWaveform(canvas: Canvas, left: Float, right: Float, centerY: Float, trackHeight: Float) {
        if (waveform.isEmpty()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.rgb(212, 216, 223)
            canvas.drawLine(left, centerY, right, centerY, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(107, 114, 128)
        val halfHeight = max(1f, trackHeight / 2f - dp(3f))
        val barWidth = max(1, (right - left).roundToInt())
        for (offset in 0 until barWidth) {
            val index = min(waveform.lastIndex, offset * waveform.size / barWidth)
            val amplitude = waveform[index].coerceIn(0f, 1f)
            val delta = max(1f, amplitude * halfHeight)
            val x = left + offset
            canvas.drawLine(x, centerY - delta, x, centerY + delta, paint)
        }
    }

    private fun nearestHandle(x: Float, left: Float, right: Float): Int {
        val startDistance = abs(x - msToX(startMs, left, right))
        val endDistance = abs(x - msToX(endMs, left, right))
        val positionDistance = abs(x - msToX(positionMs, left, right))
        return if (positionDistance <= startDistance && positionDistance <= endDistance) {
            DRAG_POSITION
        } else if (startDistance <= endDistance) {
            DRAG_START
        } else {
            DRAG_END
        }
    }

    private fun moveHandle(x: Float, left: Float, right: Float) {
        val value = xToMs(x, left, right)
        val minGap = min(MIN_GAP_MS, durationMs)
        when (dragging) {
            DRAG_START -> {
                startMs = value.coerceIn(0, endMs - minGap)
                positionMs = max(positionMs, startMs)
                emitRange()
            }
            DRAG_END -> {
                endMs = value.coerceIn(startMs + minGap, durationMs)
                positionMs = min(positionMs, endMs)
                emitRange()
            }
            DRAG_POSITION -> {
                positionMs = value.coerceIn(startMs, endMs)
                listener?.onPositionRequested(positionMs)
            }
        }
        invalidate()
    }

    private fun emitRange() {
        listener?.onRangeChanged(startMs, endMs)
    }

    private fun msToX(value: Long, left: Float, right: Float): Float {
        if (durationMs <= 0) return left
        return left + (right - left) * value / durationMs.toFloat()
    }

    private fun xToMs(x: Float, left: Float, right: Float): Long {
        val clamped = x.coerceIn(left, right)
        if (durationMs <= 0) return 0
        return (durationMs * (clamped - left) / (right - left)).toLong()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, centerX: Float, baselineY: Float) {
        val textWidth = paint.measureText(text)
        val x = (centerX - textWidth / 2f).coerceIn(0f, width - textWidth)
        canvas.drawText(text, x, baselineY, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_START = 1
        private const val DRAG_END = 2
        private const val DRAG_POSITION = 3
        private const val MIN_GAP_MS = 250L
    }
}
