package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Returns packed 0xRRGGBB for a pixel of the captured frame. */
fun interface PixelReader {
    fun rgb(x: Int, y: Int): Int
}

/**
 * Scans each configured note lane above its watch guide, then filters blobs by size, fill, and
 * within-blob hue coherence. Input targets are deliberately not used by vision.
 */
class FullScreenNoteDetector {
    private var rowFill = FloatArray(0)

    fun detect(reader: PixelReader, width: Int, height: Int, profile: CalibrationProfile): List<NoteCandidate> {
        if (width <= 0 || height <= 0) return emptyList()
        val config = profile.normalized()
        val radius = config.buttonSize * min(width, height)
        val rowStep = if (radius > 24f) 2 else 1
        if (rowFill.size < height) rowFill = FloatArray(height)
        val found = ArrayList<NoteCandidate>()

        for (lane in 0 until config.laneCount) {
            val guide = config.watchGuides[lane]
            val centerX = (guide.x * width).toInt()
            val halfWidth = config.watchLaneHalfWidth(width, height, lane).toInt()
            val xStep = if (halfWidth > 24) 2 else 1
            val scanBottom = min(height - 1, (guide.y * height - radius - 4f).toInt())
            if (scanBottom < 4) continue
            val left = max(0, centerX - halfWidth)
            val right = min(width - 1, centerX + halfWidth)

            var y = 0
            while (y <= scanBottom) {
                var active = 0
                var sampled = 0
                var x = left
                while (x <= right) {
                    val pixel = reader.rgb(x, y)
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    val hi = max(red, max(green, blue))
                    val lo = min(red, min(green, blue))
                    if (hi >= 118 && hi - lo >= 42) active++
                    sampled++
                    x += xStep
                }
                rowFill[y] = if (sampled > 0) active.toFloat() / sampled else 0f
                y += rowStep
            }

            var start = -1
            var lastActive = -1
            var fillSum = 0f
            var rows = 0
            y = 0
            while (y <= scanBottom + rowStep) {
                val isActive = y <= scanBottom && rowFill[y] >= config.sensitivity
                if (isActive) {
                    if (start < 0) {
                        start = y
                        fillSum = 0f
                        rows = 0
                    }
                    lastActive = y
                    fillSum += rowFill[y]
                    rows++
                } else if (start >= 0) {
                    val end = lastActive + rowStep
                    val blobHeight = end - start
                    val truncated = end > scanBottom
                    if (blobHeight >= max(4f, radius * 0.4f) && blobHeight <= radius * 4f) {
                        val density = min(1f, (fillSum / rows) / 0.5f)
                        val ratio = blobHeight / (2f * radius)
                        val sizeScore = (1f - min(1f, abs(ratio - 1f))).coerceAtLeast(0f)
                        val coherence = colorCoherence(reader, left, right, start, end, xStep, rowStep)
                        val confidence = 0.35f * density + 0.35f * (0.3f + 0.7f * sizeScore) + 0.3f * coherence
                        if (density >= 0.2f && coherence >= 0.5f && confidence >= config.confidenceThreshold) {
                            found.add(NoteCandidate(lane, centerX.toFloat(), (start + end) / 2f, blobHeight, confidence, truncated))
                        }
                    }
                    start = -1
                }
                y += rowStep
            }
        }
        return found
    }

    private fun colorCoherence(
        reader: PixelReader,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        xStep: Int,
        yStep: Int
    ): Float {
        val hues = IntArray(6)
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x <= right) {
                val pixel = reader.rgb(x, y)
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                val high = max(red, max(green, blue))
                val low = min(red, min(green, blue))
                val delta = high - low
                if (high >= 118 && delta >= 42) {
                    val hue = when (high) {
                        red -> 60 * (green - blue) / delta
                        green -> 120 + 60 * (blue - red) / delta
                        else -> 240 + 60 * (red - green) / delta
                    }.let { (it + 360) % 360 }
                    hues[(hue / 60).coerceIn(0, 5)]++
                    count++
                }
                x += xStep
            }
            y += yStep
        }
        if (count == 0) return 0f
        return hues.maxOrNull()!!.toFloat() / count
    }
}
