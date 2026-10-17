package dev.rhythm.autoplayer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import dev.rhythm.autoplayer.core.CalibrationProfile

/**
 * Taps the receptor positions with dispatchGesture. Only one gesture can be in flight; requests
 * that arrive meanwhile are queued, de-duplicated per lane, and dropped if they become stale.
 */
class AccessibilityInputService : AccessibilityService() {
    private class Pending(val lane: Int, val queuedAt: Long)

    private val lock = Any()
    private val queue = ArrayList<Pending>()
    private var inFlight = false
    private var inFlightSince = 0L
    private var dispatchedAt = 0L
    private var profile = CalibrationProfile()

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (active === this) active = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun enqueue(lanes: List<Int>, current: CalibrationProfile) {
        synchronized(lock) {
            profile = current
            val now = SystemClock.elapsedRealtime()
            for (lane in lanes) {
                if (lane in 0 until current.laneCount && queue.none { it.lane == lane }) queue.add(Pending(lane, now))
            }
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        val now = SystemClock.elapsedRealtime()
        if (inFlight) {
            if (now - inFlightSince > 1000L) inFlight = false else return
        }
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().queuedAt > STALE_MS) {
                iterator.remove()
                dropped++
            }
        }
        if (queue.isEmpty()) return

        val maxStrokes = GestureDescription.getMaxStrokeCount().coerceAtLeast(1)
        val batch = ArrayList<Pending>()
        while (queue.isNotEmpty() && batch.size < maxStrokes) batch.add(queue.removeAt(0))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val builder = GestureDescription.Builder()
        var strokes = 0
        for (item in batch) {
            val receptor = profile.inputTargets.getOrNull(item.lane) ?: continue
            val path = Path().apply {
                moveTo((receptor.x * width).coerceIn(1f, width - 2f), (receptor.y * height).coerceIn(1f, height - 2f))
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            strokes++
        }
        if (strokes == 0) return

        inFlight = true
        inFlightSince = now
        dispatchedAt = now
        val accepted = try {
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
            }, null)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            inFlight = false
            rejected++
        }
    }

    private fun finish(completed: Boolean) {
        synchronized(lock) {
            inFlight = false
            if (completed) {
                done++
                lastLagMs = (SystemClock.elapsedRealtime() - dispatchedAt - TAP_MS).coerceAtLeast(0L)
            } else {
                cancelled++
            }
            pumpLocked()
        }
    }

    companion object {
        const val TAP_MS = 30L
        private const val STALE_MS = 90L

        @Volatile private var active: AccessibilityInputService? = null
        @Volatile private var done = 0
        @Volatile private var cancelled = 0
        @Volatile private var rejected = 0
        @Volatile private var dropped = 0
        @Volatile private var lastLagMs = 0L

        fun isConnected(): Boolean = active != null

        /** Returns false when the accessibility service is not running. */
        fun tap(lanes: List<Int>, profile: CalibrationProfile): Boolean {
            val service = active ?: return false
            service.enqueue(lanes, profile)
            return true
        }

        fun statusLine(): String =
            "taps $done  cancelled $cancelled  rejected $rejected  stale $dropped  lag ${lastLagMs}ms"
    }
}
