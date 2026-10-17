package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Returns packed 0xRRGGBB for a pixel of the captured frame. */
fun interface PixelReader {
    fun rgb(x: Int, y: Int): Int
}

/**
 * Colour-agnostic coarse detector. For each lane it scans the column above that lane's receptor
 * button (notes scroll downward), finds saturated blobs, and reports their vertical centre.
 * Pixels are read straight from the frame buffer, only inside the lane columns.
 */
class FullScreenNoteDetector {
    private var rowFill = FloatArray(0)

    fun detect(reader: PixelReader, width: Int, height: Int, profile: CalibrationProfile): List<NoteCandidate> {
        if (width <= 0 || height <= 0) return emptyList()
        val config = profile.normalized()
        val radius = config.buttonSize * min(width, height)
        val halfWidth = max(radius * 0.8f, 3f).toInt()
        val rowStep = if (radius > 24f) 2 else 1
        val xStep = if (halfWidth > 24) 2 else 1
        if (rowFill.size < height) rowFill = FloatArray(height)
        val found = ArrayList<NoteCandidate>()

        for (lane in 0 until config.laneCount) {
            val receptor = config.receptors[lane]
            val centerX = (receptor.x * width).toInt()
            val scanBottom = min(height - 1, (receptor.y * height - radius - 4f).toInt())
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
                        val confidence = 0.5f * density + 0.5f * (0.3f + 0.7f * sizeScore)
                        if (confidence >= config.confidenceThreshold) {
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
}
