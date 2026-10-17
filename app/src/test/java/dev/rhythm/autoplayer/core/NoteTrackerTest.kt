package dev.rhythm.autoplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTrackerTest {
    @Test
    fun estimatesArrivalAndTriggersTrackOnlyOnce() {
        val tracker = NoteTracker()
        // Receptors at y = 0.82 -> 820 px on a 1000 px frame; 1.0 screens/s -> 1000 px/s.
        val profile = CalibrationProfile(laneCount = 4, fallbackSpeed = 1.0f, frameIntervalMs = 20L)
        fun candidate(y: Float) = NoteCandidate(1, 0f, y, 24, 0.8f)

        // The note enters near the top and falls at 1000 px/s, one frame every 20 ms.
        val first = tracker.update(listOf(candidate(300f)), 1000L, 1000, profile)
        assertTrue(first.isEmpty())
        val second = tracker.update(listOf(candidate(320f)), 1020L, 1000, profile)
        assertTrue(second.isEmpty())

        var dispatches = 0
        var firstDispatchY = -1f
        for (i in 2..40) {
            val y = 300f + i * 20f
            val notes = tracker.update(listOf(candidate(y)), 1000L + i * 20L, 1000, profile)
            val due = notes.filter { it.dispatchNow }
            if (due.isNotEmpty() && dispatches == 0) firstDispatchY = y
            dispatches += due.size
            if (notes.isNotEmpty()) assertEquals(1, notes.single().lane)
            if (y < 700f) assertTrue(due.isEmpty())
        }
        assertEquals(1, dispatches)
        // Fires when ~90 ms from the 820 px hit line, i.e. around y = 730.
        assertTrue(firstDispatchY in 700f..790f)
    }

    @Test
    fun movingBlobThatDidNotEnterFromTheTopIsNotANote() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4)
        var fired = false
        // A pop-up that appears mid-lane and slides down must not be treated as a falling note.
        for (i in 0 until 12) {
            val notes = tracker.update(listOf(NoteCandidate(0, 0f, 560f + i * 12f, 30, 0.9f)), 1000L + i * 20L, 1000, profile)
            if (notes.any { it.dispatchNow }) fired = true
        }
        assertFalse(fired)
    }

    private fun holdCandidate(bottom: Float, body: Float = 300f, radius: Float = 30f, scanBottom: Float = 803f): NoteCandidate? {
        val rawTop = bottom - body
        val top = maxOf(0f, rawTop)
        val visibleBottom = minOf(bottom, scanBottom)
        val height = (visibleBottom - top).toInt()
        if (height < 8) return null
        val cut = bottom > scanBottom
        return NoteCandidate(
            0, 0f, bottom - radius, height, 0.9f,
            bottomCut = cut, topCut = rawTop <= 0f, top = top, radius = radius
        )
    }

    @Test
    fun longNoteIsPressedOnceAndHeldUntilItsTail() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4, fallbackSpeed = 1.0f, frameIntervalMs = 20L)
        // 300 px tall long note (60 px head + 240 px body) falling at 1000 px/s down lane 0 (hit line at y = 820).
        var dispatches = 0
        var holdSeen = false
        var tailGap = 0L
        var stillPresentPastHead = false
        for (i in 0..55) {
            val bottom = 60f + i * 20f
            val candidates = listOfNotNull(holdCandidate(bottom))
            val notes = tracker.update(candidates, 1000L + i * 20L, 1000, profile)
            for (n in notes) {
                if (n.dispatchNow) {
                    dispatches++
                    holdSeen = n.isHold
                    tailGap = n.tailEtaMs - n.etaMs
                }
                if (n.y > 850f) stillPresentPastHead = true
            }
        }
        assertEquals(1, dispatches)
        assertTrue(holdSeen)
        // Body is ~240 px beyond the head at 1000 px/s -> the tail arrives ~240 ms after the head.
        assertTrue(tailGap in 180L..300L)
        assertTrue(stillPresentPastHead)
    }

    @Test
    fun unverifiedStaticObjectNeverTriggers() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4)
        var fired = false
        for (i in 0 until 20) {
            val notes = tracker.update(listOf(NoteCandidate(0, 0f, 790f, 24, 0.9f)), 1000L + i * 20L, 1000, profile)
            if (notes.any { it.dispatchNow }) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun oneFrameArtifactNeverBecomesVisibleOrTriggers() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4)
        val first = tracker.update(listOf(NoteCandidate(0, 0f, 500f, 24, 0.9f)), 1000L, 1000, profile)
        assertTrue(first.isEmpty())
        for (frame in 1..20) {
            val notes = tracker.update(emptyList(), 1000L + frame * 20L, 1000, profile)
            assertTrue(notes.isEmpty())
        }
    }

    @Test
    fun flashingRatingPopupsAreIgnoredAndDoNotBlockRealNotes() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4, fallbackSpeed = 1.0f, frameIntervalMs = 20L)
        var popupFired = false
        // "SICK"/"PERFECT" popups flash in the same spot in lane 0 with a few px of jitter.
        for (burst in 0 until 5) {
            for (i in 0 until 6) {
                val t = 1000L + burst * 300L + i * 20L
                val y = 400f + if (i % 2 == 0) 0f else 3f
                val notes = tracker.update(listOf(NoteCandidate(0, 0f, y, 30, 0.9f)), t, 1000, profile)
                if (notes.any { it.lane == 0 }) popupFired = true
            }
            tracker.update(emptyList(), 1000L + burst * 300L + 200L, 1000, profile)
        }
        assertFalse(popupFired)

        // A real note then scrolls down lane 1 at 1000 px/s and must still be tapped exactly once.
        var fires = 0
        for (i in 0..30) {
            val notes = tracker.update(listOf(NoteCandidate(1, 0f, 300f + i * 20f, 40, 0.8f)), 3000L + i * 20L, 1000, profile)
            fires += notes.count { it.dispatchNow && it.lane == 1 }
        }
        assertEquals(1, fires)
    }

    @Test
    fun slowlyDriftingPopupNeverFires() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4)
        var fired = false
        for (i in 0 until 40) {
            val notes = tracker.update(listOf(NoteCandidate(0, 0f, 500f + i * 3f, 30, 0.9f)), 1000L + i * 20L, 1000, profile)
            if (notes.any { it.dispatchNow }) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun tracksFastNotesWhenSpeedGuessIsWrong() {
        val tracker = NoteTracker()
        // Guess is 1.0 screen/s but the real speed is 3.0 screens/s at ~30 fps.
        val profile = CalibrationProfile(laneCount = 4, fallbackSpeed = 1.0f, frameIntervalMs = 20L)
        var fires = 0
        for (i in 0..20) {
            val notes = tracker.update(listOf(NoteCandidate(2, 0f, 100f + i * 100f, 60, 0.8f)), 1000L + i * 33L, 1000, profile)
            fires += notes.count { it.dispatchNow }
        }
        assertEquals(1, fires)
    }
}
