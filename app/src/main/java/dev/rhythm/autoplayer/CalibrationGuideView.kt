package dev.rhythm.autoplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class CalibrationGuideView(context: Context, private val changed: (List<Float>, Float) -> Unit) : View(context) {
    private val backgroundPaint = Paint().apply { color = 0xff172b2a.toInt() }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff40d8bb.toInt(); strokeWidth = 2f }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffc43d.toInt(); strokeWidth = 4f }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff4f6f5.toInt() }
    private var centers = listOf(0.125f, 0.375f, 0.625f, 0.875f)
    private var hitY = 0.82f
    private var dragLane = -1
    private var draggingHitLine = false

    fun setGuides(laneCenters: List<Float>, receptorY: Float) {
        centers = laneCenters.toList()
        hitY = receptorY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8f, 8f, backgroundPaint)
        val top = height * 0.08f
        val bottom = height * 0.92f
        centers.forEachIndexed { index, center ->
            val x = center * width
            canvas.drawLine(x, top, x, bottom, lanePaint)
            canvas.drawCircle(x, height * 0.48f, 7f, notePaint)
            canvas.drawText((index + 1).toString(), x - 4f, height * 0.16f, notePaint)
        }
        canvas.drawLine(0f, hitY * height, width.toFloat(), hitY * height, hitPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHitLine = kotlin.math.abs(event.y / height - hitY) < 0.12f
                dragLane = if (draggingHitLine) -1 else centers.indices.minByOrNull { kotlin.math.abs(centers[it] * width - event.x) } ?: -1
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHitLine) hitY = (event.y / height).coerceIn(0.1f, 0.9f)
                else if (dragLane >= 0 && centers.isNotEmpty()) {
                    centers = centers.toMutableList().also { it[dragLane] = (event.x / width).coerceIn(0.01f, 0.99f) }
                }
                changed(centers, hitY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragLane = -1
                draggingHitLine = false
                return true
            }
        }
        return true
    }
}