package dev.rhythm.autoplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenNoteDetectorTest {
    @Test
    fun detectsColoredNoteAtConfiguredLaneAcrossFrame() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        for (y in 300 until 340) for (x in 45 until 75) pixels[y * width + x] = 0xffff5040.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(pixels, width, height, profile)

        assertEquals(1, notes.size)
        assertEquals(0, notes.single().lane)
        assertTrue(notes.single().y in 300f..340f)
    }

    @Test
    fun supportsNonFourLaneLayoutAndUpScroll() {
        val width = 420
        val height = 700
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        for (y in 120 until 155) for (x in 296 until 324) pixels[y * width + x] = 0xff30c0f0.toInt()
        val profile = CalibrationProfile(
            laneCount = 7,
            direction = ScrollDirection.UP,
            confidenceThreshold = 0.35f
        )

        val notes = FullScreenNoteDetector().detect(pixels, width, height, profile)

        assertEquals(1, notes.size)
        assertEquals(5, notes.single().lane)
    }
}