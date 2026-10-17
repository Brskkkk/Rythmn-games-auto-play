package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of a single in-memory calibration frame. */
data class JudgementCalibrationResult(
    val profile: CalibrationProfile,
    val adjustedLanes: Int,
    val radiusAdjusted: Boolean
)

/**
 * Refines manually placed watch guides by searching near each guide for a horizontal judgement
 * receptor/line. This intentionally does not guess lane X positions: the user must place those
 * approximately first. It works on the live frame buffer and never stores image data.
 */
object JudgementLineCalibrator {
    fun calibrate(
        reader: PixelReader,
        width: Int,
        height: Int,
        profile: CalibrationProfile
    ): JudgementCalibrationResult? {
        if (width < 32 || height < 32) return null
        val config = profile.normalized()
        val minSide = min(width, height).toFloat()
        val refined = config.watchGuides.toMutableList()
        val radii = ArrayList<Float>()
        var adjusted = 0

        for (lane in config.watchGuides.indices) {
            val guide = config.watchGuides[lane]
            val centerX = (guide.x * width).toInt().coerceIn(1, width - 2)
            val halfWidth = config.watchLaneHalfWidth(width, height, lane).toInt().coerceAtLeast(5)
            val left = max(1, centerX - halfWidth)
            val right = min(width - 2, centerX + halfWidth)
            val oldY = (guide.y * height).toInt().coerceIn(4, height - 5)
            val search = max(8, (height * 0.045f).toInt())
            val top = max(4, oldY - search)
            val bottom = min(height - 5, oldY + search)
            var bestY = oldY
            var bestScore = 0f

            for (y in top..bottom) {
                var total = 0L
                var strong = 0
                var samples = 0
                val step = max(1, (right - left) / 48)
                var x = left
                while (x <= right) {
                    val here = reader.rgb(x, y)
                    val above = reader.rgb(x, y - 2)
                    val below = reader.rgb(x, y + 2)
                    val contrast = (colorDistance(here, above) + colorDistance(here, below)) / 2
                    total += contrast
                    if (contrast >= 34) strong++
                    samples++
                    x += step
                }
                if (samples == 0) continue
                val average = total.toFloat() / samples
                val coverage = strong.toFloat() / samples
                // Prefer a clear horizontal edge that spans a meaningful part of this lane.
                val score = average * (0.35f + coverage * 0.65f)
                if (score > bestScore) {
                    bestScore = score
                    bestY = y
                }
            }

            // Avoid moving a guide based on random background texture alone.
            if (bestScore >= 20f) {
                refined[lane] = Receptor(guide.x, bestY.toFloat() / height)
                adjusted++
                val radius = estimateHorizontalRadius(reader, centerX, bestY, width, height, halfWidth)
                    ?: estimateVerticalRadius(reader, centerX, bestY, width, height, halfWidth)
                if (radius != null) radii.add(radius / minSide)
            }
        }

        if (adjusted == 0) return null
        val measuredSize = if (radii.isNotEmpty()) radii.sorted()[radii.size / 2].coerceIn(0.02f, 0.15f) else config.buttonSize
        val next = config.copy(
            watchGuides = refined,
            buttonSize = if (radii.size >= max(1, config.laneCount / 2)) measuredSize else config.buttonSize
        ).normalized()
        return JudgementCalibrationResult(next, adjusted, next.buttonSize != config.buttonSize)
    }

    /** Estimates the receptor's visible diameter across its center row, including ring outlines. */
    private fun estimateHorizontalRadius(
        reader: PixelReader,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        laneHalfWidth: Int
    ): Float? {
        val left = max(1, centerX - laneHalfWidth)
        val right = min(width - 2, centerX + laneHalfWidth)
        val edgeOffset = 4
        if (centerY - edgeOffset < 0 || centerY + edgeOffset >= height) return null
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (x in left..right) {
            val here = reader.rgb(x, centerY)
            val above = reader.rgb(x, centerY - edgeOffset)
            val below = reader.rgb(x, centerY + edgeOffset)
            val contrast = (colorDistance(here, above) + colorDistance(here, below)) / 2
            if (contrast >= 34) {
                first = min(first, x)
                last = max(last, x)
            }
        }
        if (first == Int.MAX_VALUE || last <= first) return null
        val diameter = (last - first + 1).toFloat()
        if (diameter < 8f || diameter > laneHalfWidth * 2.35f) return null
        return diameter / 2f
    }

    private fun estimateVerticalRadius(
        reader: PixelReader,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        laneHalfWidth: Int
    ): Float? {
        val maxRadius = min(min(width, height) * 0.09f, laneHalfWidth * 0.8f).toInt().coerceAtLeast(4)
        val xOffset = max(3, laneHalfWidth / 2)
        fun contrastAt(y: Int): Int {
            if (y !in 1 until height - 1) return 0
            val x1 = (centerX - xOffset).coerceIn(0, width - 1)
            val x2 = (centerX + xOffset).coerceIn(0, width - 1)
            val background = averageColor(reader.rgb(x1, y), reader.rgb(x2, y))
            return colorDistance(reader.rgb(centerX, y), background)
        }
        val centerScore = contrastAt(centerY)
        if (centerScore < 24) return null
        var top = centerY
        var bottom = centerY
        while (centerY - top < maxRadius && contrastAt(top) >= max(18, centerScore / 3)) top--
        while (bottom - centerY < maxRadius && contrastAt(bottom) >= max(18, centerScore / 3)) bottom++
        val diameter = (bottom - top).toFloat()
        if (diameter < 6f || diameter > maxRadius * 1.8f) return null
        return diameter / 2f
    }

    private fun averageColor(a: Int, b: Int): Int {
        val r = ((a ushr 16 and 255) + (b ushr 16 and 255)) / 2
        val g = ((a ushr 8 and 255) + (b ushr 8 and 255)) / 2
        val bl = ((a and 255) + (b and 255)) / 2
        return (r shl 16) or (g shl 8) or bl
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs((a ushr 16 and 255) - (b ushr 16 and 255)) +
            abs((a ushr 8 and 255) - (b ushr 8 and 255)) +
            abs((a and 255) - (b and 255))
}
