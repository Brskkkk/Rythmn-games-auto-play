package dev.rhythm.autoplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTrackerTest {
    @Test
    fun estimatesArrivalAndTriggersTrackOnlyOnce() {
        val tracker = NoteTracker()
        val profile = CalibrationProfile(laneCount = 4, hitLineY = 0.82f, fallbackSpeedPxPerSecond = 1000f)
        fun candidate(y: Float) = NoteCandidate(1, 0f, y, 40, 24, 0.8f)

        val initial = tracker.update(listOf(candidate(600f)), 1000L, 1000, profile)
        assertFalse(initial.single().dispatchNow)
        tracker.update(listOf(candidate(660f)), 1060L, 1000, profile)
        tracker.update(listOf(candidate(720f)), 1120L, 1000, profile)
        val approaching = tracker.update(listOf(candidate(770f)), 1170L, 1000, profile)
        assertTrue(approaching.single().dispatchNow)
        assertTrue(approaching.single().holdDurationMs >= 42L)
        val repeated = tracker.update(listOf(candidate(790f)), 1190L, 1000, profile)
        assertFalse(repeated.single().dispatchNow)
        assertEquals(1, repeated.single().lane)
    }
}