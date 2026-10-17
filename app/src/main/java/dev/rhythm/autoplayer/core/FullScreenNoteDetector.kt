package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Returns packed 0xRRGGBB for a pixel of the captured frame. */
fun interface PixelReader {
    fun rgb(x: Int, y: Int): Int
}

/**
 * Scans each configured note lane above its watch guide. A pixel counts as "note" when it differs
 * clearly from that lane's own background colour (estimated per frame as the median of the lane
 * rows), so white, grey and dim notes work as well as saturated ones. Blobs are then filtered by
 * size, fill and colour uniformity. Static things (rating pop-ups, flashes) are rejected later by
 * [NoteTracker], which requires real top-to-bottom motion. Tall blobs are long notes.
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
            // Cover the whole lane from the top of the screen down to just above the hit line.
            val scanBottom = min(height - 1, (guide.y * height - radius * 0.5f - 2f).toInt())
            if (scanBottom < 4) continue
            val left = max(0, centerX - halfWidth)
            val right = min(width - 1, centerX + halfWidth)

            val bg = estimateBackground(reader, left, right, scanBottom, xStep)
            val threshold = bg[3]

            var y = 0
            while (y <= scanBottom) {
                var active = 0
                var sampled = 0
                var x = left
                while (x <= right) {
                    if (isForeground(reader.rgb(x, y), bg, threshold)) active++
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
            var gap = 0
            y = 0
            // Allow a 1-row gap inside a note (outline/gloss lines) so one note is not split in two.
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
                    gap = 0
                } else if (start >= 0 && y <= scanBottom && gap < rowStep * 2 && gap + rowStep < 3) {
                    gap += rowStep
                } else if (start >= 0) {
                    val end = lastActive + rowStep
                    val blobHeight = end - start
                    val bottomCut = end > scanBottom
                    val topCut = start <= rowStep
                    if (blobHeight >= max(4f, radius * 0.4f) && blobHeight <= height) {
                        val density = min(1f, (fillSum / rows) / 0.5f)
                        val ratio = min(blobHeight.toFloat(), 2f * radius) / (2f * radius)
                        val sizeScore = (1f - min(1f, abs(ratio - 1f))).coerceAtLeast(0f)
                        val coherence = colorCoherence(reader, left, right, start, end, xStep, rowStep, bg, threshold)
                        val confidence = 0.35f * density + 0.35f * (0.3f + 0.7f * sizeScore) + 0.3f * coherence
                        if (density >= 0.2f && coherence >= 0.4f && confidence >= config.confidenceThreshold) {
                            // Notes fall downward, so the bottom edge is the leading edge. Anchoring on it
                            // keeps short notes, notes still entering from the top, and long notes consistent.
                            found.add(
                                NoteCandidate(
                                    lane, centerX.toFloat(), end - radius, blobHeight, confidence,
                                    bottomCut = bottomCut, topCut = topCut, top = start.toFloat(), radius = radius
                                )
                            )
                        }
                    }
                    start = -1
                    gap = 0
                }
                y += rowStep
            }
        }
        return found
    }

    /** Returns [r, g, b, threshold]: median lane colour and an adaptive difference threshold. */
    private fun estimateBackground(reader: PixelReader, left: Int, right: Int, bottom: Int, xStep: Int): FloatArray {
        val sampleStep = max(3, bottom / 120)
        val count = bottom / sampleStep + 1
        val rs = IntArray(count)
        val gs = IntArray(count)
        val bs = IntArray(count)
        var n = 0
        var y = 0
        while (y <= bottom && n < count) {
            var r = 0
            var g = 0
            var b = 0
            var c = 0
            var x = left
            while (x <= right) {
                val p = reader.rgb(x, y)
                r += p shr 16 and 0xff
                g += p shr 8 and 0xff
                b += p and 0xff
                c++
                x += max(1, xStep * 2)
            }
            if (c > 0) {
                rs[n] = r / c
                gs[n] = g / c
                bs[n] = b / c
                n++
            }
            y += sampleStep
        }
        if (n == 0) return floatArrayOf(0f, 0f, 0f, 60f)
        val mr = median(rs, n)
        val mg = median(gs, n)
        val mb = median(bs, n)
        val dev = IntArray(n) { abs(rs[it] - mr) + abs(gs[it] - mg) + abs(bs[it] - mb) }
        val mad = median(dev, n)
        // Busy/gradient backgrounds get a higher bar; clean ones keep a sensitive bar.
        val threshold = (60f + 2.5f * mad).coerceIn(60f, 170f)
        return floatArrayOf(mr.toFloat(), mg.toFloat(), mb.toFloat(), threshold)
    }

    private fun median(values: IntArray, n: Int): Int {
        val copy = values.copyOf(n)
        copy.sort()
        return copy[n / 2]
    }

    private fun isForeground(pixel: Int, bg: FloatArray, threshold: Float): Boolean {
        val dr = (pixel shr 16 and 0xff) - bg[0]
        val dg = (pixel shr 8 and 0xff) - bg[1]
        val db = (pixel and 0xff) - bg[2]
        // Manhattan distance is cheap and good enough here.
        return abs(dr) + abs(dg) + abs(db) >= threshold
    }

    /**
     * Fraction of foreground pixels close to the blob's mean colour. Solid notes score high; random
     * multicolour noise scores low. Gradients and outlines still pass because the radius is generous.
     */
    private fun colorCoherence(
        reader: PixelReader,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        xStep: Int,
        yStep: Int,
        bg: FloatArray,
        threshold: Float
    ): Float {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x <= right) {
                val pixel = reader.rgb(x, y)
                if (isForeground(pixel, bg, threshold)) {
                    sumR += pixel shr 16 and 0xff
                    sumG += pixel shr 8 and 0xff
                    sumB += pixel and 0xff
                    count++
                }
                x += xStep
            }
            y += yStep
        }
        if (count == 0) return 0f
        val mr = sumR / count
        val mg = sumG / count
        val mb = sumB / count
        var close = 0
        y = top
        while (y < bottom) {
            var x = left
            while (x <= right) {
                val pixel = reader.rgb(x, y)
                if (isForeground(pixel, bg, threshold)) {
                    val d = abs((pixel shr 16 and 0xff) - mr) +
                        abs((pixel shr 8 and 0xff) - mg) +
                        abs((pixel and 0xff) - mb)
                    if (d <= 150) close++
                }
                x += xStep
            }
            y += yStep
        }
        return close.toFloat() / count
    }
}
