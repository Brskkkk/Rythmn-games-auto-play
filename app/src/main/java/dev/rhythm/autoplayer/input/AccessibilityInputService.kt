package dev.rhythm.autoplayer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.rhythm.autoplayer.core.CalibrationProfile

/**
 * Taps and holds the input targets with dispatchGesture. Only one gesture can be in flight; requests
 * that arrive meanwhile are queued, de-duplicated per lane, and dropped if they become stale.
 *
 * Long notes: a hold starts a stroke with willContinue = true and then keeps it alive by chaining
 * short continuation strokes (every gesture re-includes all held lanes, plus any new taps) until the
 * lane's release time, when the last chunk is sent with willContinue = false.
 */
class AccessibilityInputService : AccessibilityService() {
    /** [releaseAt] == 0 means an ordinary tap; otherwise a hold that ends at that elapsedRealtime. */
    private class Pending(val lane: Int, val queuedAt: Long, val releaseAt: Long)

    private class Held(val lane: Int, var stroke: GestureDescription.StrokeDescription, var releaseAt: Long)

    private val lock = Any()
    private val queue = ArrayList<Pending>()
    private val held = LinkedHashMap<Int, Held>()
    private var inFlight = false
    private var inFlightSince = 0L
    private var dispatchedAt = 0L
    private var profile = CalibrationProfile()

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        synchronized(lock) { held.clear() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (active === this) active = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun enqueue(lanes: List<Int>, current: CalibrationProfile, releaseAt: Long) {
        synchronized(lock) {
            profile = current
            val now = SystemClock.elapsedRealtime()
            for (lane in lanes) {
                if (lane !in 0 until current.laneCount) continue
                val existing = held[lane]
                if (existing != null) {
                    // Already down on this lane: only a longer hold may extend it.
                    if (releaseAt > existing.releaseAt) existing.releaseAt = releaseAt
                    continue
                }
                val i = queue.indexOfFirst { it.lane == lane }
                if (i < 0) {
                    queue.add(Pending(lane, now, releaseAt))
                } else if (releaseAt > queue[i].releaseAt) {
                    queue[i] = Pending(lane, queue[i].queuedAt, releaseAt)
                }
            }
            pumpLocked()
        }
    }

    private fun setRelease(lane: Int, releaseAt: Long) {
        synchronized(lock) {
            held[lane]?.releaseAt = releaseAt
            val i = queue.indexOfFirst { it.lane == lane && it.releaseAt > 0L }
            if (i >= 0) queue[i] = Pending(lane, queue[i].queuedAt, releaseAt)
        }
    }

    private fun releaseEverything() {
        synchronized(lock) {
            queue.removeAll { it.releaseAt > 0L }
            for (h in held.values) h.releaseAt = 0L // ends on the next chunk
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        val now = SystemClock.elapsedRealtime()
        if (inFlight) {
            if (now - inFlightSince > 1000L) {
                inFlight = false
                held.clear()
            } else {
                return
            }
        }
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().queuedAt > STALE_MS) {
                iterator.remove()
                dropped++
            }
        }
        if (queue.isEmpty() && held.isEmpty()) return

        val maxStrokes = GestureDescription.getMaxStrokeCount().coerceAtLeast(1)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        fun pathFor(lane: Int): Path? {
            val target = profile.inputTargets.getOrNull(lane) ?: return null
            return Path().apply {
                moveTo((target.x * width).coerceIn(1f, width - 2f), (target.y * height).coerceIn(1f, height - 2f))
            }
        }

        val builder = GestureDescription.Builder()
        var strokes = 0
        val ending = ArrayList<Int>()
        val started = ArrayList<Held>()

        // 1) Keep every held finger down (or lift it on its final chunk).
        for (h in held.values) {
            if (strokes >= maxStrokes) break
            val path = pathFor(h.lane)
            if (path == null) {
                ending.add(h.lane)
                continue
            }
            val remaining = h.releaseAt - now
            val last = remaining <= HOLD_CHUNK_MS
            val duration = if (last) remaining.coerceAtLeast(1L) else HOLD_CHUNK_MS
            val next = h.stroke.continueStroke(path, 0L, duration, !last)
            builder.addStroke(next)
            h.stroke = next
            if (last) ending.add(h.lane)
            strokes++
        }

        // 2) New taps / holds.
        val batch = ArrayList<Pending>()
        while (queue.isNotEmpty() && strokes + batch.size < maxStrokes) batch.add(queue.removeAt(0))
        for (item in batch) {
            val path = pathFor(item.lane) ?: continue
            if (item.releaseAt > 0L) {
                val stroke = GestureDescription.StrokeDescription(path, 0L, HOLD_CHUNK_MS, true)
                builder.addStroke(stroke)
                started.add(Held(item.lane, stroke, item.releaseAt))
            } else {
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            }
            strokes++
        }
        for (lane in ending) held.remove(lane)
        if (strokes == 0) return
        for (h in started) held[h.lane] = h

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
            held.clear()
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
                // A cancelled gesture lifts every finger, so nothing is held any more.
                held.clear()
            }
            pumpLocked()
        }
    }

    companion object {
        const val TAP_MS = 30L
        /** Length of each continuation chunk while a long note is held. */
        const val HOLD_CHUNK_MS = 60L
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
            service.enqueue(lanes, profile, 0L)
            return true
        }

        /** Presses and holds [lanes] until [releaseAtMs] (SystemClock.elapsedRealtime base). */
        fun press(lanes: List<Int>, profile: CalibrationProfile, releaseAtMs: Long): Boolean {
            val service = active ?: return false
            service.enqueue(lanes, profile, releaseAtMs)
            return true
        }

        /** Moves the planned release time of a held (or about to be held) lane. */
        fun updateRelease(lane: Int, releaseAtMs: Long) {
            active?.setRelease(lane, releaseAtMs)
        }

        /** Lifts every held finger and forgets pending holds. */
        fun releaseAll() {
            active?.releaseEverything()
        }

        fun statusLine(): String =
            "taps $done  cancelled $cancelled  rejected $rejected  stale $dropped  lag ${lastLagMs}ms"
    }
}
