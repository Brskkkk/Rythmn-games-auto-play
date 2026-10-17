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
    @Volatile var guideLayer = GuideLayer.WATCH_GUIDES
        set(value) {
            field = value
            dragIndex = -1
            postInvalidate()
        }

    @Volatile var editing = true
        set(value) {
            field = value
            dragIndex = -1
            postInvalidate()
        }

    private var watchGuides: MutableList<Receptor> = ArrayList()
    private var inputTargets: MutableList<Receptor> = ArrayList()
    private var buttonSize = 0.05f
    @Volatile private var showMarkers = true
    @Volatile private var markers: List<PredictedNote> = emptyList()
    @Volatile private var frameHeight = 1
    private var dragIndex = -1

    private val density = context.resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = 0x33000000 }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x263DFF4A; style = Paint.Style.FILL }
    private val watchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7DFF66.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val inputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF72C6.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val inputFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FF72C6; style = Paint.Style.FILL }
    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA101C1B.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC43D.toInt(); style = Paint.Style.FILL }
    private val holdMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DD6FF.toInt(); style = Paint.Style.FILL }
    private val fireMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4D4D.toInt(); style = Paint.Style.FILL }

    fun setProfile(profile: CalibrationProfile) {
        watchGuides = profile.watchGuides.toMutableList()
        inputTargets = profile.inputTargets.toMutableList()
        buttonSize = profile.buttonSize
        showMarkers = profile.showMarkers
        postInvalidate()
    }

    fun snapshotWatchGuides(): List<Receptor> = watchGuides.toList()

    fun snapshotInputTargets(): List<Receptor> = inputTargets.toList()

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
        }
        watchGuides.forEachIndexed { index, guide ->
            val x = guide.x * w
            val bottom = guide.y * h
            val zoneHalfWidth = (buttonSize * min(width, height) * 0.8f)
                .coerceAtMost(profileLaneSpacingHalfWidth(index, w))
                .coerceAtLeast(3f)
            if (editing) {
                // Only tint the lane while editing: in Watch/Play the overlay is captured too, and
                // a coloured strip over the lane would look like a note to the detector.
                canvas.drawRect(x - zoneHalfWidth, 0f, x + zoneHalfWidth, bottom, zonePaint)
                canvas.drawLine(x, 0f, x, bottom, watchPaint)
            }
            canvas.drawLine(x - zoneHalfWidth, bottom, x + zoneHalfWidth, bottom, watchPaint)
            if (editing && guideLayer == GuideLayer.WATCH_GUIDES) {
                canvas.drawCircle(x, bottom, r, inputFillPaint)
                canvas.drawCircle(x, bottom, r, watchPaint)
                textPaint.textSize = max(r * 0.7f, 10f * density)
                canvas.drawText("W${index + 1}", x, bottom + r * 0.25f, textPaint)
            }
        }
        inputTargets.forEachIndexed { index, target ->
            val x = target.x * w
            val y = target.y * h
            val targetRadius = if (editing && guideLayer == GuideLayer.INPUT_TARGETS) r else max(r * 0.48f, 7f * density)
            if (editing && guideLayer == GuideLayer.INPUT_TARGETS) canvas.drawCircle(x, y, targetRadius, inputFillPaint)
            canvas.drawCircle(x, y, targetRadius, inputPaint)
            if (editing && guideLayer == GuideLayer.INPUT_TARGETS) {
                textPaint.textSize = max(targetRadius * 0.7f, 10f * density)
                canvas.drawText("T${index + 1}", x, y + targetRadius * 0.25f, textPaint)
            }
        }
        if (!editing && showMarkers) {
            val scale = h / frameHeight
            for (note in markers) {
                val rec = watchGuides.getOrNull(note.lane) ?: continue
                val x = rec.x * w + r * 1.05f
                val paint = when {
                    note.dispatchNow -> fireMarkerPaint
                    note.isHold -> holdMarkerPaint
                    else -> markerPaint
                }
                canvas.drawCircle(x, note.y * scale, (if (note.isHold) 7f else 5f) * density, paint)
            }
        }
        if (editing) {
            textPaint.textSize = 13f * density
            val label = if (guideLayer == GuideLayer.WATCH_GUIDES) "LIME: drag watch lanes and hit lines" else "PINK: drag touch targets independently"
            val labelWidth = textPaint.measureText(label)
            val baseline = 42f * density
            canvas.drawRoundRect(w / 2f - labelWidth / 2f - 8f * density, baseline - 18f * density,
                w / 2f + labelWidth / 2f + 8f * density, baseline + 5f * density, 6f * density, 6f * density, textBackgroundPaint)
            canvas.drawText(label, w / 2f, baseline, textPaint)
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
                val points = activePoints()
                for (i in points.indices) {
                    val d = hypot(points[i].x * w - event.x, points[i].y * h - event.y)
                    if (d < bestDistance) {
                        bestDistance = d
                        best = i
                    }
                }
                dragIndex = if (bestDistance <= grab) best else -1
                return dragIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val points = activePoints()
                if (dragIndex < 0 || dragIndex >= points.size) return false
                val nx = (event.x / w).coerceIn(0.01f, 0.99f)
                val ny = (event.y / h).coerceIn(0.05f, 0.98f)
                if (linkRows) {
                    for (i in points.indices) {
                        points[i] = Receptor(if (i == dragIndex) nx else points[i].x, ny)
                    }
                } else {
                    points[dragIndex] = Receptor(nx, ny)
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
        val points = activePoints()
        val n = points.size
        if (n < 2) return
        val first = points.first()
        val last = points.last()
        val y = (first.y + last.y) / 2f
        for (i in 0 until n) points[i] = Receptor(first.x + (last.x - first.x) * i / (n - 1), y)
        invalidate()
        onChanged?.invoke()
    }

    private fun activePoints(): MutableList<Receptor> =
        if (guideLayer == GuideLayer.WATCH_GUIDES) watchGuides else inputTargets

    private fun profileLaneSpacingHalfWidth(lane: Int, screenWidth: Float): Float {
        val x = watchGuides.getOrNull(lane)?.x ?: return screenWidth
        val gap = watchGuides.indices.asSequence()
            .filter { it != lane }
            .map { kotlin.math.abs(watchGuides[it].x - x) }
            .minOrNull() ?: 1f
        return gap * screenWidth * 0.45f
    }
}
