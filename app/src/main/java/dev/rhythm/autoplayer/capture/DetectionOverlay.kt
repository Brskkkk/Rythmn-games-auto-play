package dev.rhythm.autoplayer.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.PredictedNote

class DetectionOverlay(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 225, 190)
        strokeWidth = 2f
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 61)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private var profile = CalibrationProfile()
    private var notes: List<PredictedNote> = emptyList()
    private var frameWidth = 1
    private var frameHeight = 1
    private var status = ""

    fun update(profile: CalibrationProfile, notes: List<PredictedNote>, width: Int, height: Int, status: String) {
        this.profile = profile
        this.notes = notes
        frameWidth = width.coerceAtLeast(1)
        frameHeight = height.coerceAtLeast(1)
        this.status = status
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width.toFloat() / frameWidth
        val sy = height.toFloat() / frameHeight
        for (lane in 0 until profile.laneCount) {
            val x = profile.laneCenters[lane] * width
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        val hitY = profile.hitLineY * height
        canvas.drawLine(0f, hitY, width.toFloat(), hitY, linePaint)
        notes.forEach { note ->
            val x = note.x * width
            val y = note.y * sy
            val half = (frameWidth / profile.laneCount * sx * 0.3f).coerceAtLeast(12f)
            canvas.drawRoundRect(RectF(x - half, y - 14f, x + half, y + 14f), 5f, 5f, notePaint)
            canvas.drawText("${note.lane + 1}  ${note.etaMs}ms", x + half + 4f, y + 6f, labelPaint)
        }
        if (status.isNotEmpty()) canvas.drawText(status, 20f, 40f, labelPaint)
    }
}