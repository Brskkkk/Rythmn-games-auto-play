package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Associates detections across frames, estimates scroll speed, projects each note to its
 * receptor, and raises `dispatchNow` exactly once per note when it is close enough to schedule a tap.
 */
class NoteTracker {
    private class Track(
        val id: Long,
        val lane: Int,
        var y: Float,
        var lastSeenMs: Long,
        var confidence: Float
    ) {
        var velocity = 0f
        var sightings = 1
        var consistentMotionSamples = 0
        var previousMeasuredVelocity = 0f
        var verified = false
        var fired = false
    }

    private val tracks = ArrayList<Track>()
    private val lastFireMs = LongArray(CalibrationProfile.MAX_LANES) { Long.MIN_VALUE / 2 }
    private var nextId = 1L
    private var globalSpeed = 0f
    private var globalSamples = 0
    private var outlierRun = 0

    fun update(candidates: List<NoteCandidate>, nowMs: Long, frameHeight: Int, profile: CalibrationProfile): List<PredictedNote> {
        val config = profile.normalized()
        val height = frameHeight.toFloat()
        val fallbackPx = config.fallbackSpeed * height
        val used = HashSet<Long>()

        for (candidate in candidates.filter { !it.truncated }.sortedByDescending { it.y }) {
            var best: Track? = null
            var bestDistance = Float.MAX_VALUE
            for (track in tracks) {
                if (track.lane != candidate.lane || track.id in used || nowMs <= track.lastSeenMs) continue
                val dt = (nowMs - track.lastSeenMs) / 1000f
                val speed = speedOf(track, fallbackPx)
                val projected = track.y + speed * dt
                val range = max(0.03f * height, 0.6f * speed * dt + 0.02f * height)
                val distance = abs(projected - candidate.y)
                if (distance <= range && distance < bestDistance) {
                    best = track
                    bestDistance = distance
                }
            }
            if (best == null) {
                if (nowMs - lastFireMs[candidate.lane] < 60L) continue
                tracks.add(Track(nextId++, candidate.lane, candidate.y, nowMs, candidate.confidence))
            } else {
                val dt = (nowMs - best.lastSeenMs) / 1000f
                val measured = (candidate.y - best.y) / dt
                if (measured > 0.005f * height && measured < 10f * height) {
                    val consistent = best.previousMeasuredVelocity == 0f ||
                        measured in (best.previousMeasuredVelocity * 0.45f)..(best.previousMeasuredVelocity * 2.2f)
                    best.consistentMotionSamples = if (consistent) best.consistentMotionSamples + 1 else 1
                    best.previousMeasuredVelocity = measured
                    best.velocity = if (best.velocity == 0f) measured else best.velocity * 0.6f + measured * 0.4f
                    if (best.consistentMotionSamples >= 2) best.verified = true
                    updateGlobalSpeed(measured)
                } else {
                    best.consistentMotionSamples = 0
                    best.previousMeasuredVelocity = 0f
                }
                best.y = candidate.y
                best.lastSeenMs = nowMs
                best.sightings++
                best.confidence = candidate.confidence
                used.add(best.id)
            }
        }

        val horizonMs = config.frameIntervalMs + 70L
        val result = ArrayList<PredictedNote>()
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            val unseenMs = nowMs - track.lastSeenMs
            if (unseenMs > (if (track.fired) 700L else 450L)) {
                iterator.remove()
                continue
            }
            val speed = max(speedOf(track, fallbackPx), 1f)
            val projected = track.y + speed * unseenMs / 1000f
            val hitY = config.watchGuides[track.lane.coerceIn(0, config.laneCount - 1)].y * height
            val distance = hitY - projected
            if (distance < -0.02f * height) {
                iterator.remove()
                continue
            }
            val eta = max(0L, (distance * 1000f / speed).toLong())
            val dispatchNow = !track.fired && track.verified && eta <= horizonMs
            if (dispatchNow) {
                track.fired = true
                lastFireMs[track.lane] = nowMs
            }
            if (track.verified) result.add(PredictedNote(track.id, track.lane, projected, eta, track.confidence, dispatchNow))
        }
        return result
    }

    private fun speedOf(track: Track, fallbackPx: Float): Float = when {
        globalSamples >= 3 && globalSpeed > 0f -> globalSpeed
        track.velocity > 0f -> track.velocity
        else -> fallbackPx
    }

    private fun updateGlobalSpeed(measured: Float) {
        if (globalSamples == 0) {
            globalSpeed = measured
            globalSamples = 1
            return
        }
        if (measured in (globalSpeed * 0.6f)..(globalSpeed * 1.6f)) {
            globalSpeed = globalSpeed * 0.88f + measured * 0.12f
            if (globalSamples < 1000) globalSamples++
            outlierRun = 0
        } else if (++outlierRun > 8) {
            globalSpeed = measured
            globalSamples = 1
            outlierRun = 0
        }
    }

    fun clear() {
        tracks.clear()
        globalSpeed = 0f
        globalSamples = 0
        outlierRun = 0
    }
}
