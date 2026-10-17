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

        val initial = tracker.update(listOf(candidate(600f)), 1000L, 1000, profile)
        assertTrue(initial.isEmpty())
        val confirmed = tracker.update(listOf(candidate(660f)), 1060L, 1000, profile)
        assertTrue(confirmed.isEmpty())
        val far = tracker.update(listOf(candidate(720f)), 1120L, 1000, profile)
        assertFalse(far.single().dispatchNow)
        val approaching = tracker.update(listOf(candidate(770f)), 1170L, 1000, profile)
        assertTrue(approaching.single().dispatchNow)
        assertEquals(1, approaching.single().lane)
        val repeated = tracker.update(listOf(candidate(790f)), 1190L, 1000, profile)
        assertFalse(repeated.single().dispatchNow)
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
}
