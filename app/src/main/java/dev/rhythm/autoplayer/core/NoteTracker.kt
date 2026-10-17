package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max

class NoteTracker {
    private data class Track(
        val id: Long,
        val lane: Int,
        var y: Float,
        var height: Int,
        var lastSeenMs: Long,
        var velocity: Float,
        var confidence: Float,
        var motionVerified: Boolean = false,
        var fired: Boolean = false
    )

    private val tracks = ArrayList<Track>()
    private var nextId = 1L

    fun update(candidates: List<NoteCandidate>, nowMs: Long, frameHeight: Int, profile: CalibrationProfile): List<PredictedNote> {
        val config = profile.normalized()
        val used = HashSet<Long>()
        for (candidate in candidates) {
            val track = tracks.asSequence()
                .filter { it.lane == candidate.lane && it.id !in used && nowMs > it.lastSeenMs }
                .map { existing ->
                    val elapsed = nowMs - existing.lastSeenMs
                    val expectedSpeed = max(abs(existing.velocity), config.fallbackSpeedPxPerSecond)
                    val associationRange = max(frameHeight * 0.07f, expectedSpeed * elapsed / 1000f * 1.75f + candidate.height)
                    existing to associationRange
                }
                .filter { (existing, range) -> abs(existing.y - candidate.y) <= range }
                .minByOrNull { (existing, _) -> abs(existing.y - candidate.y) }
                ?.first
            if (track == null) {
                tracks.add(Track(nextId++, candidate.lane, candidate.y, candidate.height, nowMs, 0f, candidate.confidence))
            } else {
                val elapsed = (nowMs - track.lastSeenMs).coerceAtLeast(1L)
                val measured = (candidate.y - track.y) * 1000f / elapsed
                if (measured * config.direction.sign > 0f) {
                    track.velocity = if (track.velocity == 0f) measured else track.velocity * 0.65f + measured * 0.35f
                    if (abs(measured) > 80f) track.motionVerified = true
                }
                track.y = candidate.y
                track.height = candidate.height
                track.lastSeenMs = nowMs
                track.confidence = candidate.confidence
                used.add(track.id)
            }
        }

        val hitY = config.hitLineY * frameHeight
        val result = ArrayList<PredictedNote>()
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (nowMs - track.lastSeenMs > 350L) {
                iterator.remove()
                continue
            }
            val speed = if (track.velocity * config.direction.sign > 80f) {
                abs(track.velocity)
            } else config.fallbackSpeedPxPerSecond
            val distance = (hitY - track.y) * config.direction.sign
            if (distance <= 0f) {
                iterator.remove()
                continue
            }
            val eta = (distance * 1000f / max(speed, 1f)).toLong()
            val holdDuration = (track.height * 1000f / max(speed, 1f)).toLong().coerceIn(42L, 30_000L)
            val dispatchNow = track.motionVerified && eta <= max(70L, config.frameIntervalMs) && !track.fired
            result.add(PredictedNote(track.id, track.lane, config.laneCenters[track.lane], track.y, eta, track.confidence, holdDuration, dispatchNow))
            if (dispatchNow) track.fired = true
        }
        return result
    }

    fun clear() = tracks.clear()
}