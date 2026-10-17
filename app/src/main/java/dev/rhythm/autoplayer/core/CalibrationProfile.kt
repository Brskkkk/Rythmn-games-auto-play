package dev.rhythm.autoplayer.core

enum class ScrollDirection(val sign: Int) {
    DOWN(1),
    UP(-1)
}

data class CalibrationProfile(
    val name: String = "Default",
    val laneCount: Int = 4,
    val laneCenters: List<Float> = evenlySpacedCenters(4),
    val hitLineY: Float = 0.82f,
    val direction: ScrollDirection = ScrollDirection.DOWN,
    val sensitivity: Float = 0.18f,
    val confidenceThreshold: Float = 0.45f,
    val timingOffsetMs: Long = 0L,
    val fallbackSpeedPxPerSecond: Float = 900f,
    val frameIntervalMs: Long = 33L,
    val observationOnly: Boolean = true,
    val overlayEnabled: Boolean = true
) {
    fun normalized(): CalibrationProfile {
        val safeCount = laneCount.coerceIn(1, 32)
        val centers = if (laneCenters.size == safeCount) laneCenters else evenlySpacedCenters(safeCount)
        return copy(
            laneCount = safeCount,
            laneCenters = centers.map { it.coerceIn(0f, 1f) },
            hitLineY = hitLineY.coerceIn(0.05f, 0.95f),
            sensitivity = sensitivity.coerceIn(0.02f, 0.8f),
            confidenceThreshold = confidenceThreshold.coerceIn(0.2f, 0.95f),
            timingOffsetMs = timingOffsetMs.coerceIn(-500L, 500L),
            fallbackSpeedPxPerSecond = fallbackSpeedPxPerSecond.coerceIn(100f, 5000f),
            frameIntervalMs = frameIntervalMs.coerceIn(16L, 250L)
        )
    }

    companion object {
        fun evenlySpacedCenters(laneCount: Int): List<Float> =
            (0 until laneCount.coerceIn(1, 32)).map { (it + 0.5f) / laneCount }
    }
}

data class NoteCandidate(
    val lane: Int,
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int,
    val confidence: Float
)

data class PredictedNote(
    val id: Long,
    val lane: Int,
    val x: Float,
    val y: Float,
    val etaMs: Long,
    val confidence: Float,
    val holdDurationMs: Long = 42L,
    val dispatchNow: Boolean = false
)

data class FrameDiagnostics(
    val width: Int,
    val height: Int,
    val detectionCount: Int,
    val processingMs: Long,
    val captureToDetectionMs: Long,
    val confidence: Float,
    val fps: Float,
    val notes: List<PredictedNote>
)