package dev.rhythm.autoplayer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.rhythm.autoplayer.core.CalibrationProfile

class AccessibilityInputService : AccessibilityService() {
    @Volatile private var gestureInFlight = false

    override fun onServiceConnected() {
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    @Synchronized
    fun dispatch(lanes: List<Int>, holdDurationsMs: Map<Int, Long>, profile: CalibrationProfile): Boolean {
        val requested = lanes.distinct()
        if (requested.isEmpty() || gestureInFlight) {
            report(if (gestureInFlight) "busy" else "empty")
            return false
        }
        if (requested.size > 20) {
            report("too_many_strokes")
            return false
        }
        val displaySize = Point()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(displaySize)
        val screenWidth = displaySize.x.coerceAtLeast(1)
        val screenHeight = displaySize.y.coerceAtLeast(1)
        val builder = GestureDescription.Builder()
        requested.forEach { lane ->
            if (lane !in 0 until profile.laneCount) return@forEach
            val x = (profile.laneCenters[lane] * screenWidth).coerceIn(1f, (screenWidth - 2).toFloat())
            val y = (profile.hitLineY * screenHeight).coerceIn(1f, (screenHeight - 2).toFloat())
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
            }
            val duration = (holdDurationsMs[lane] ?: 42L).coerceIn(42L, 30_000L)
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        }
        gestureInFlight = true
        val accepted = runCatching {
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finishGesture("completed")
                override fun onCancelled(gestureDescription: GestureDescription?) = finishGesture("cancelled")
            }, null)
        }.getOrDefault(false)
        if (!accepted) {
            gestureInFlight = false
            report("rejected")
        }
        return accepted
    }

    @Synchronized
    private fun finishGesture(result: String) {
        gestureInFlight = false
        report(result)
    }

    private fun report(result: String) {
        sendBroadcast(Intent(ACTION_INPUT_RESULT).setPackage(packageName).putExtra(EXTRA_RESULT, result))
    }

    companion object {
        const val ACTION_INPUT_RESULT = "dev.rhythm.autoplayer.INPUT_RESULT"
        const val EXTRA_RESULT = "result"
        @Volatile private var active: AccessibilityInputService? = null

        fun isConnected(): Boolean = active != null

        fun dispatch(lanes: List<Int>, holdDurationsMs: Map<Int, Long>, profile: CalibrationProfile): Boolean =
            active?.dispatch(lanes, holdDurationsMs, profile) ?: false
    }
}