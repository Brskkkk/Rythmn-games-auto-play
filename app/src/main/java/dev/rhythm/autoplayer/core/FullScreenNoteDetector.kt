package dev.rhythm.autoplayer.core

/** Color-agnostic coarse detector. It analyzes the whole frame, then assigns blobs to configured lanes. */
class FullScreenNoteDetector {
    fun detect(pixels: IntArray, width: Int, height: Int, profile: CalibrationProfile): List<NoteCandidate> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val config = profile.normalized()
        val sampleStep = if (width * height > 1_500_000) 3 else 2
        val laneWidth = (width / config.laneCount * 0.72f).toInt().coerceAtLeast(8)
        val found = ArrayList<NoteCandidate>()

        for (lane in 0 until config.laneCount) {
            val centerX = (config.laneCenters[lane] * width).toInt()
            val left = (centerX - laneWidth / 2).coerceAtLeast(0)
            val right = (centerX + laneWidth / 2).coerceAtMost(width - 1)
            val activeRows = BooleanArray(height)
            for (y in 0 until height step sampleStep) {
                var active = 0
                var sampled = 0
                for (x in left..right step sampleStep) {
                    val pixel = pixels[y * width + x]
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    val max = maxOf(red, green, blue)
                    val min = minOf(red, green, blue)
                    if (max >= 118 && max - min >= 42) active++
                    sampled++
                }
                if (sampled > 0 && active.toFloat() / sampled >= config.sensitivity) {
                    for (row in y until minOf(y + sampleStep, height)) activeRows[row] = true
                }
            }

            var start = -1
            for (y in 0..height) {
                val isActive = y < height && activeRows[y]
                if (isActive && start < 0) start = y
                if (!isActive && start >= 0) {
                    val blobHeight = y - start
                    val midY = (start + y) / 2f
                    val distanceToHit = (config.hitLineY * height - midY) * config.direction.sign
                    if (blobHeight >= 8 && blobHeight <= height * 0.16f && distanceToHit > 12f) {
                        val confidence = (0.35f + blobHeight / (height * 0.12f)).coerceIn(0.35f, 0.96f)
                        if (confidence >= config.confidenceThreshold) {
                            found.add(NoteCandidate(lane, centerX.toFloat(), midY, laneWidth, blobHeight, confidence))
                        }
                    }
                    start = -1
                }
            }
        }
        return found
    }
}