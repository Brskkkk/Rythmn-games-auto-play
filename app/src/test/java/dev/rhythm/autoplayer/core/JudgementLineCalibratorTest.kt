package dev.rhythm.autoplayer.core

import org.junit.Assert.assertNotNull
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgementLineCalibratorTest {
    @Test fun refinesGuidesTowardHighContrastJudgementLines() {
        val width = 400
        val height = 300
        val lineYs = listOf(220, 222, 219, 221)
        val centers = listOf(100, 160, 220, 280)
        val reader = PixelReader { x, y ->
            val onLine = centers.indices.any { lane ->
                x in (centers[lane] - 20)..(centers[lane] + 20) && abs(y - lineYs[lane]) <= 1
            }
            if (onLine) 0xFFFFFF else 0x202020
        }
        val guides = centers.mapIndexed { i, x -> Receptor(x / width.toFloat(), 0.72f) }
        val profile = CalibrationProfile(
            laneCount = 4,
            watchGuides = guides,
            inputTargets = guides,
            buttonSize = 0.05f
        )
        val result = JudgementLineCalibrator.calibrate(reader, width, height, profile)
        assertNotNull(result)
        assertTrue(result!!.adjustedLanes >= 3)
        assertTrue(result.profile.watchGuides.zip(lineYs).all { (guide, y) -> kotlin.math.abs(guide.y * height - y) <= 6f })
    }
}
