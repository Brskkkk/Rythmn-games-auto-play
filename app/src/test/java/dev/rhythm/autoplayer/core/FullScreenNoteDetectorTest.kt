package dev.rhythm.autoplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenNoteDetectorTest {
    private fun reader(pixels: IntArray, width: Int) = PixelReader { x, y -> pixels[y * width + x] }

    @Test
    fun detectsColoredNoteAboveItsReceptor() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // 4 lanes: lane 0 is centred on x = 140. A 24x40 note well above the receptor (y = 656).
        for (y in 300 until 340) for (x in 128 until 152) pixels[y * width + x] = 0xffff5040.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
        assertEquals(0, notes.single().lane)
        assertTrue(notes.single().y in 300f..340f)
        assertTrue(!notes.single().truncated)
    }

    @Test
    fun supportsSevenLanes() {
        val width = 420
        val height = 700
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // 7 lanes: lane 5 is centred on x = 294.
        for (y in 120 until 155) for (x in 282 until 306) pixels[y * width + x] = 0xff30c0f0.toInt()
        val profile = CalibrationProfile(
            laneCount = 7,
            watchGuides = CalibrationProfile.defaultReceptors(7),
            confidenceThreshold = 0.35f
        )

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
        assertEquals(5, notes.single().lane)
    }

    @Test
    fun ignoresTheReceptorItself() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // Receptor for lane 0 sits at y = 656 with radius 20: its own pixels must not count as a note.
        for (y in 640 until 672) for (x in 124 until 156) pixels[y * width + x] = 0xff40ff40.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertTrue(notes.isEmpty())
    }

    @Test
    fun scansWatchGuidesIndependentlyOfInputTargets() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        for (y in 300 until 340) for (x in 228 until 252) pixels[y * width + x] = 0xffff5040.toInt()
        val profile = CalibrationProfile(laneCount = 4).copy(
            watchGuides = listOf(Receptor(0.6f, 0.82f), Receptor(0.4f, 0.82f), Receptor(0.5f, 0.82f), Receptor(0.7f, 0.82f)),
            inputTargets = CalibrationProfile.defaultReceptors(4)
        )

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
        assertEquals(0, notes.single().lane)
    }

    @Test
    fun rejectsColorfulNoiseWithIncoherentHue() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        val colors = intArrayOf(0xffff3030.toInt(), 0xff30ff30.toInt(), 0xff3030ff.toInt())
        for (y in 300 until 340) for (x in 128 until 152) {
            pixels[y * width + x] = colors[(x + y) % colors.size]
        }
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertTrue(notes.isEmpty())
    }

    @Test
    fun watchGuideEditsDoNotMoveTapTargets() {
        val profile = CalibrationProfile()
        val targets = profile.inputTargets
        val moved = profile.withWatchGuides(profile.watchGuides.mapIndexed { index, point ->
            if (index == 0) Receptor(0.2f, 0.6f) else point
        })

        assertEquals(targets, moved.inputTargets)
        assertEquals(0.2f, moved.watchGuides[0].x)
    }

    @Test
    fun detectsWhiteAndGreyNotes() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // Lane 0 white note, lane 1 dim grey note. Neither is "colourful".
        for (y in 300 until 340) for (x in 128 until 152) pixels[y * width + x] = 0xffffffff.toInt()
        for (y in 200 until 240) for (x in 168 until 192) pixels[y * width + x] = 0xff707070.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(setOf(0, 1), notes.map { it.lane }.toSet())
    }

    @Test
    fun detectsNoteOnBrighterLaneBackground() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff808080.toInt() }
        for (y in 300 until 340) for (x in 128 until 152) pixels[y * width + x] = 0xff101010.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
    }

    @Test
    fun longNoteIsOneTallBlobAnchoredOnItsBottomEdge() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // Head at the bottom (y 380..420) with a 200 px body above it, all in lane 0.
        for (y in 220 until 420) for (x in 128 until 152) pixels[y * width + x] = 0xff40d0ff.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
        val note = notes.single()
        assertTrue(note.height in 190..210)
        // Head centre = bottom edge (420) minus one radius (20).
        assertTrue(note.y in 395f..405f)
        assertTrue(!note.bottomCut)
    }

    @Test
    fun noteEnteringFromTopIsReportedAsPartiallyVisible() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        for (y in 0 until 25) for (x in 128 until 152) pixels[y * width + x] = 0xffff5040.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
        assertTrue(notes.single().topCut)
    }

    @Test
    fun scansTheWholeLaneDownToTheHitLine() {
        val width = 400
        val height = 800
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        // Hit line is at y = 656 (r = 20). A note just above it, at y 604..644, must be found.
        for (y in 604 until 644) for (x in 128 until 152) pixels[y * width + x] = 0xffff5040.toInt()
        val profile = CalibrationProfile(laneCount = 4, confidenceThreshold = 0.35f)

        val notes = FullScreenNoteDetector().detect(reader(pixels, width), width, height, profile)

        assertEquals(1, notes.size)
    }
}
