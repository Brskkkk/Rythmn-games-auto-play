package dev.rhythm.autoplayer.core

import kotlin.math.abs

/** Screen position of one lane's receptor button, as a fraction of the real screen (0..1). */
data class Receptor(val x: Float, val y: Float)

/**
 * One calibration profile. Notes are assumed to scroll DOWN toward the receptors.
 * Note guides control scan lanes and their hit lines; input targets independently control taps.
 */
data class CalibrationProfile(
    val name: String = "Default",
    val laneCount: Int = 4,
    val watchGuides: List<Receptor> = defaultReceptors(4),
    val inputTargets: List<Receptor> = defaultReceptors(4),
    /** Button radius as a fraction of the shorter screen side. */
    val buttonSize: Float = 0.05f,
    val sensitivity: Float = 0.18f,
    val confidenceThreshold: Float = 0.45f,
    /** Positive = tap earlier, negative = tap later. */
    val timingOffsetMs: Long = 0L,
    /** Note speed guess (screen heights per second) used until real motion is measured. */
    val fallbackSpeed: Float = 1.0f,
    val frameIntervalMs: Long = 20L,
    val showMarkers: Boolean = true
) {
    fun normalized(): CalibrationProfile {
        val count = laneCount.coerceIn(1, MAX_LANES)
        val watches = if (watchGuides.size == count) watchGuides else resize(watchGuides, count)
        val targets = if (inputTargets.size == count) inputTargets else resize(inputTargets, count)
        return copy(
            laneCount = count,
            watchGuides = watches.map { Receptor(it.x.coerceIn(0.01f, 0.99f), it.y.coerceIn(0.05f, 0.98f)) },
            inputTargets = targets.map { Receptor(it.x.coerceIn(0.01f, 0.99f), it.y.coerceIn(0.05f, 0.98f)) },
            buttonSize = buttonSize.coerceIn(0.02f, 0.15f),
            sensitivity = sensitivity.coerceIn(0.02f, 0.8f),
            confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.95f),
            timingOffsetMs = timingOffsetMs.coerceIn(-500L, 500L),
            fallbackSpeed = fallbackSpeed.coerceIn(0.1f, 5f),
            frameIntervalMs = frameIntervalMs.coerceIn(8L, 250L)
        )
    }

    fun withLaneCount(count: Int): CalibrationProfile {
        val safe = count.coerceIn(1, MAX_LANES)
        return copy(
            laneCount = safe,
            watchGuides = resize(watchGuides, safe),
            inputTargets = resize(inputTargets, safe)
        ).normalized()
    }

    fun withWatchGuides(list: List<Receptor>): CalibrationProfile =
        if (list.isEmpty()) this else copy(laneCount = list.size, watchGuides = list).normalized()

    fun withInputTargets(list: List<Receptor>): CalibrationProfile =
        if (list.isEmpty()) this else copy(laneCount = list.size, inputTargets = list).normalized()

    fun watchLaneHalfWidth(width: Int, height: Int, lane: Int): Float {
        val center = watchGuides.getOrNull(lane)?.x ?: return 3f
        val nearestGap = watchGuides.indices.asSequence()
            .filter { it != lane }
            .map { kotlin.math.abs(watchGuides[it].x - center) }
            .minOrNull() ?: 1f
        val noteRadius = buttonSize * minOf(width, height)
        return minOf(noteRadius * 0.8f, nearestGap * width * 0.45f).coerceAtLeast(3f)
    }

    companion object {
        const val MAX_LANES = 32

        fun defaultReceptors(count: Int): List<Receptor> {
            val n = count.coerceIn(1, MAX_LANES)
            val spacing = minOf(0.1f, 0.8f / n)
            return (0 until n).map { Receptor(0.5f + (it - (n - 1) / 2f) * spacing, 0.82f) }
        }

        /** Keeps existing positions, then extends/truncates to [count]. */
        fun resize(list: List<Receptor>, count: Int): List<Receptor> {
            if (list.isEmpty()) return defaultReceptors(count)
            if (count <= list.size) return list.take(count)
            val last = list.last()
            var step = if (list.size >= 2) last.x - list[list.size - 2].x else 0.1f
            if (abs(step) < 0.02f) step = 0.08f
            val result = ArrayList<Receptor>(list)
            for (k in 1..(count - list.size)) result.add(Receptor(last.x + step * k, last.y))
            if (result.any { it.x > 0.97f || it.x < 0.03f }) {
                return (0 until count).map {
                    Receptor(0.05f + 0.9f * it / maxOf(1, count - 1), last.y)
                }
            }
            return result
        }
    }
}

data class NoteCandidate(
    val lane: Int,
    val x: Float,
    /** Vertical centre, in captured-frame pixels. */
    val y: Float,
    val height: Int,
    val confidence: Float,
    /** True when the blob touches the edge of the scan zone, so its centre is unreliable. */
    val truncated: Boolean = false
)

data class PredictedNote(
    val id: Long,
    val lane: Int,
    /** Projected vertical position in captured-frame pixels. */
    val y: Float,
    val etaMs: Long,
    val confidence: Float,
    val dispatchNow: Boolean = false
)
