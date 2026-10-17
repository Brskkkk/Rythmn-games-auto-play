package dev.rhythm.autoplayer.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.PredictedNote
import dev.rhythm.autoplayer.core.Receptor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay that draws one draggable ring per lane. While [editing] it accepts touches
 * (drag rings onto the game's receptors). Otherwise the hosting window is NOT_TOUCHABLE so
 * accessibility taps pass through to the game.
 */
class ReceptorOverlayView(context: Context) : View(context) {
    var onChanged: (() -> Unit)? = null
    var linkRows = true

    @Volatile var editing = true
        set(value) {
            field = value
            dragIndex = -1
            postInvalidate()
        }

    private var receptors: MutableList<Receptor> = ArrayList()
    private var buttonSize = 0.05f
    @Volatile private var showMarkers = true
    @Volatile private var markers: List<PredictedNote> = emptyList()
    @Volatile private var frameHeight = 1
    private var dragIndex = -1

    private val density = context.resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = 0x33000000 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFC43D; style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC43D.toInt(); style = Paint.Style.FILL }
    private val fireMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4D4D.toInt(); style = Paint.Style.FILL }

    fun setProfile(profile: CalibrationProfile) {
        receptors = profile.receptors.toMutableList()
        buttonSize = profile.buttonSize
        showMarkers = profile.showMarkers
        postInvalidate()
    }

    fun snapshot(): List<Receptor> = receptors.toList()

    fun setMarkers(notes: List<PredictedNote>, frameHeightPx: Int) {
        markers = notes
        frameHeight = max(1, frameHeightPx)
        postInvalidateOnAnimation()
    }

    private fun radiusPx(): Float = buttonSize * min(width, height)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = radiusPx()
        if (editing) {
            canvas.drawRect(0f, 0f, w, h, dimPaint)
            textPaint.textSize = 16f * density
            canvas.drawText("Drag each circle onto its receptor. Tap Watch or Play when done.", w / 2f, 28f * density + 56f * density, textPaint)
        }
        ringPaint.color = if (editing) 0xFFFFC43D.toInt() else 0xFF28E1BE.toInt()
        textPaint.textSize = max(r * 0.8f, 10f)
        val list = receptors
        for (i in list.indices) {
            val cx = list[i].x * w
            val cy = list[i].y * h
            if (editing) {
                canvas.drawCircle(cx, cy, r, fillPaint)
            }
            canvas.drawCircle(cx, cy, r, ringPaint)
            if (editing) canvas.drawText((i + 1).toString(), cx, cy + r * 0.28f, textPaint)
        }
        if (!editing && showMarkers) {
            val scale = h / frameHeight
            for (note in markers) {
                val rec = list.getOrNull(note.lane) ?: continue
                val x = rec.x * w + r * 1.05f
                canvas.drawCircle(x, note.y * scale, 5f * density, if (note.dispatchNow) fireMarkerPaint else markerPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grab = max(radiusPx() * 1.6f, 44f * density)
                var best = -1
                var bestDistance = Float.MAX_VALUE
                for (i in receptors.indices) {
                    val d = hypot(receptors[i].x * w - event.x, receptors[i].y * h - event.y)
                    if (d < bestDistance) {
                        bestDistance = d
                        best = i
                    }
                }
                dragIndex = if (bestDistance <= grab) best else -1
                return dragIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex < 0 || dragIndex >= receptors.size) return false
                val nx = (event.x / w).coerceIn(0.01f, 0.99f)
                val ny = (event.y / h).coerceIn(0.05f, 0.98f)
                if (linkRows) {
                    for (i in receptors.indices) {
                        receptors[i] = Receptor(if (i == dragIndex) nx else receptors[i].x, ny)
                    }
                } else {
                    receptors[dragIndex] = Receptor(nx, ny)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragIndex >= 0
                dragIndex = -1
                if (was) onChanged?.invoke()
                return was
            }
        }
        return false
    }

    /** Spaces lanes evenly in x between the first and last button, and aligns y to their average. */
    fun spreadEvenly() {
        val n = receptors.size
        if (n < 2) return
        val first = receptors.first()
        val last = receptors.last()
        val y = (first.y + last.y) / 2f
        for (i in 0 until n) receptors[i] = Receptor(first.x + (last.x - first.x) * i / (n - 1), y)
        invalidate()
        onChanged?.invoke()
    }
}
