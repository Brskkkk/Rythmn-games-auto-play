package dev.rhythm.autoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Associates detections across frames, estimates scroll speed, projects each note to its hit line,
 * and raises `dispatchNow` exactly once per note when it is close enough to schedule a tap.
 *
 * Rules that keep it honest on a downscroll game:
 *  - a blob only becomes a real note after it has travelled DOWNWARD a meaningful distance at a
 *    steady speed, so things that pop up and stay put (sick/perfect ratings, flashes) are ignored;
 *  - it must have entered from the top of the lane (or travel far at the known scroll speed);
 *  - tall blobs are long notes: the head is tracked by the bottom edge, and once the bottom runs
 *    into the hit zone the head is followed through the top edge, so the tail time is known.
 */
class NoteTracker {
    private class Track(
        val id: Long,
        val lane: Int,
        y: Float,
        t: Long,
        var confidence: Float,
        var radius: Float,
        val enteredFromTop: Boolean
    ) {
        val ts = ArrayList<Long>()
        val ys = ArrayList<Float>()
        /** Time of the last position update (used for motion maths). */
        var lastSeenMs = t
        /** Time of the last match of any kind, including stationary (used for aging). */
        var lastMatchMs = t
        var y = y
        var velocity = 0f
        var stationaryHits = 0
        var isStatic = false
        var verified = false
        var fired = false
        /** Largest blob height seen: 2 * radius for a tap note, much more for a long note. */
        var length = 0f

        init {
            ts.add(t)
            ys.add(y)
        }

        val sightings get() = ts.size
        val travelled get() = ys.last() - ys.first()
        val isHold get() = length > radius * 3.2f

        fun add(t: Long, y: Float) {
            ts.add(t)
            ys.add(y)
            if (ts.size > HISTORY) {
                ts.removeAt(0)
                ys.removeAt(0)
            }
            lastSeenMs = t
            lastMatchMs = t
            this.y = y
        }

        /** Least-squares speed (px/s) over the stored history, or null when not enough spread. */
        fun fitSpeed(): Float? {
            val n = ts.size
            if (n < 2) return null
            val t0 = ts[0]
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val x = (ts[i] - t0) / 1000.0
                val yy = ys[i].toDouble()
                sx += x
                sy += yy
                sxx += x * x
                sxy += x * yy
            }
            val denom = n * sxx - sx * sx
            if (denom < 1e-9) return null
            return ((n * sxy - sx * sy) / denom).toFloat()
        }

        /** Largest deviation from the straight-line fit, in px. */
        fun residual(speed: Float): Float {
            val n = ts.size
            val t0 = ts[0]
            var mean = 0.0
            for (i in 0 until n) mean += ys[i] - speed * (ts[i] - t0) / 1000.0
            mean /= n
            var worst = 0f
            for (i in 0 until n) {
                val e = abs(ys[i] - (mean + speed * (ts[i] - t0) / 1000.0)).toFloat()
                if (e > worst) worst = e
            }
            return worst
        }
    }

    private val tracks = ArrayList<Track>()
    private val lastFireMs = LongArray(CalibrationProfile.MAX_LANES) { Long.MIN_VALUE / 2 }
    private var nextId = 1L
    private val speedSamples = ArrayList<Float>()
    private var outlierRun = 0

    /** Median of recent verified note speeds in px/s, or 0 until at least 3 agree. */
    private val globalSpeed: Float
        get() {
            if (speedSamples.size < 3) return 0f
            val sorted = speedSamples.sorted()
            return sorted[sorted.size / 2]
        }

    /** Head position for [c] given what we already know about [track]; NaN when it cannot be known. */
    private fun anchorOf(c: NoteCandidate, track: Track): Float = when {
        !c.bottomCut -> c.y
        track.length > 0f -> c.top + track.length - c.radius
        else -> Float.NaN
    }

    fun update(candidates: List<NoteCandidate>, nowMs: Long, frameHeight: Int, profile: CalibrationProfile): List<PredictedNote> {
        val config = profile.normalized()
        val height = frameHeight.toFloat()
        val fallbackPx = config.fallbackSpeed * height
        val knownSpeed = globalSpeed
        val minSpeed = MIN_SPEED_SCREENS * height
        val maxSpeed = MAX_SPEED_SCREENS * height
        val staticTol = STATIC_TOL_SCREENS * height
        val minTravel = MIN_TRAVEL_SCREENS * height

        // ---- build all valid candidate<->track pairs, then assign cheapest first --------------
        class Link(val c: Int, val t: Int, val cost: Float, val stationary: Boolean, val anchor: Float)

        val pairs = ArrayList<Link>()
        for ((ci, cand) in candidates.withIndex()) {
            for ((ti, track) in tracks.withIndex()) {
                if (track.lane != cand.lane || nowMs <= track.lastSeenMs) continue
                val anchor = anchorOf(cand, track)
                if (anchor.isNaN()) continue
                val dt = (nowMs - track.lastSeenMs) / 1000f
                val dy = anchor - track.y
                if (abs(dy) <= staticTol) {
                    // Hasn't moved: same blob sitting still (or too slow to tell yet).
                    pairs.add(Link(ci, ti, abs(dy) + 0.5f, true, anchor))
                    continue
                }
                if (dy < 0f || track.isStatic) continue // notes only fall; static blobs never move
                val speed = dy / dt
                val expected = when {
                    knownSpeed > 0f -> knownSpeed
                    track.velocity > 0f -> track.velocity
                    else -> fallbackPx
                }
                val ok = if (knownSpeed > 0f) {
                    speed in (knownSpeed * 0.55f)..(knownSpeed * 1.8f)
                } else {
                    speed in max(minSpeed, fallbackPx * 0.15f)..min(maxSpeed, fallbackPx * 8f)
                }
                if (!ok) continue
                pairs.add(Link(ci, ti, abs(speed - expected) * dt, false, anchor))
            }
        }
        pairs.sortBy { it.cost }
        val candUsed = BooleanArray(candidates.size)
        val trackUsed = BooleanArray(tracks.size)
        for (p in pairs) {
            if (candUsed[p.c] || trackUsed[p.t]) continue
            candUsed[p.c] = true
            trackUsed[p.t] = true
            val track = tracks[p.t]
            val cand = candidates[p.c]
            track.confidence = cand.confidence
            track.radius = cand.radius
            track.length = max(track.length, cand.height.toFloat())
            if (p.stationary) {
                track.stationaryHits++
                track.lastMatchMs = nowMs
                // Don't move the anchor for stationary matches so slow drift still accumulates.
                if (track.stationaryHits >= STATIC_HITS && track.travelled < minTravel) track.isStatic = true
                continue
            }
            track.stationaryHits = 0
            track.add(nowMs, p.anchor)
            evaluate(track, height, config, knownSpeed, minTravel, minSpeed, maxSpeed)
        }

        // ---- unmatched candidates start new tracks (unless they duplicate an existing one) -----
        for ((ci, cand) in candidates.withIndex()) {
            if (candUsed[ci] || cand.bottomCut) continue
            if (nowMs - lastFireMs[cand.lane] < 60L) continue
            val near = max(0.03f * height, cand.radius * 1.4f)
            val duplicate = tracks.any { it.lane == cand.lane && abs(it.y - cand.y) < near && nowMs - it.lastMatchMs < 120L }
            if (duplicate) continue
            val hitY = config.watchGuides[cand.lane.coerceIn(0, config.laneCount - 1)].y * height
            val entered = cand.topCut || cand.y <= SPAWN_FRACTION * hitY
            val track = Track(nextId++, cand.lane, cand.y, nowMs, cand.confidence, cand.radius, entered)
            track.length = cand.height.toFloat()
            tracks.add(track)
        }

        // ---- age out, project, dispatch --------------------------------------------------------
        val horizonMs = config.frameIntervalMs + 70L
        val result = ArrayList<PredictedNote>()
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            val unseenMs = nowMs - track.lastSeenMs
            val sinceMatchMs = nowMs - track.lastMatchMs
            val maxUnseen = when {
                track.isStatic -> 300L
                track.fired -> 700L
                track.verified -> 450L
                else -> 250L
            }
            if (sinceMatchMs > maxUnseen) {
                iterator.remove()
                continue
            }
            if (!track.verified) continue
            val trackSpeed = track.velocity
            val speed = max(
                1f,
                when {
                    knownSpeed > 0f && trackSpeed in (knownSpeed * 0.7f)..(knownSpeed * 1.4f) -> knownSpeed
                    trackSpeed > 0f -> trackSpeed
                    knownSpeed > 0f -> knownSpeed
                    else -> fallbackPx
                }
            )
            val projected = track.y + speed * unseenMs / 1000f
            val hitY = config.watchGuides[track.lane.coerceIn(0, config.laneCount - 1)].y * height
            val distance = hitY - projected
            // Tail (top edge) of a long note reaches the hit line this many px later than the head.
            val bodyPx = if (track.isHold) max(0f, track.length - 2f * track.radius) else 0f
            val tailDistance = distance + bodyPx
            val endDistance = if (track.isHold) tailDistance else distance
            if (endDistance < -0.02f * height) {
                iterator.remove()
                continue
            }
            val eta = max(0L, (distance * 1000f / speed).toLong())
            val tailEta = max(eta, (tailDistance * 1000f / speed).toLong())
            val dispatchNow = !track.fired && eta <= horizonMs
            if (dispatchNow) {
                track.fired = true
                lastFireMs[track.lane] = nowMs
            }
            result.add(PredictedNote(track.id, track.lane, projected, eta, track.confidence, dispatchNow, track.isHold, tailEta))
        }
        return result
    }

    /** Decides whether a moving track has earned "real note" status and updates the speed model. */
    private fun evaluate(
        track: Track,
        height: Float,
        config: CalibrationProfile,
        knownSpeed: Float,
        minTravel: Float,
        minSpeed: Float,
        maxSpeed: Float
    ) {
        val speed = track.fitSpeed() ?: return
        if (speed < minSpeed || speed > maxSpeed) {
            if (!track.verified) track.velocity = 0f
            return
        }
        track.velocity = speed
        if (track.verified) {
            // Keep the speed model fresh from long, clean tracks only.
            if (track.sightings == 5) recordSpeed(speed)
            return
        }
        val steady = track.residual(speed) <= max(0.02f * height, 0.25f * track.travelled)
        val enoughTravel = track.travelled >= minTravel
        val matchesKnown = knownSpeed > 0f && speed in (knownSpeed * 0.6f)..(knownSpeed * 1.6f)
        // Falling notes spawn at the top. A late first sighting is only trusted if it then travels
        // far at the known scroll speed - something a pop-up can never do.
        val fromTop = track.enteredFromTop || (matchesKnown && track.travelled >= 0.2f * height)
        // Once the scroll speed is known, a candidate must match it; before that, any steady
        // downward mover with enough travel qualifies (static pop-ups never do).
        if (fromTop && steady && enoughTravel && track.sightings >= 3 && (knownSpeed <= 0f || matchesKnown)) {
            track.verified = true
            recordSpeed(speed)
        }
    }

    private fun recordSpeed(measured: Float) {
        val current = globalSpeed
        if (current > 0f && measured !in (current * 0.6f)..(current * 1.6f)) {
            // Speed genuinely changed (song/scroll change)? Only follow after many agreeing outliers.
            if (++outlierRun > 8) {
                speedSamples.clear()
                outlierRun = 0
                speedSamples.add(measured)
            }
            return
        }
        outlierRun = 0
        speedSamples.add(measured)
        if (speedSamples.size > SPEED_WINDOW) speedSamples.removeAt(0)
    }

    fun clear() {
        tracks.clear()
        speedSamples.clear()
        outlierRun = 0
    }

    private companion object {
        const val HISTORY = 8
        /** Slowest believable note speed, in screen heights per second. */
        const val MIN_SPEED_SCREENS = 0.2f
        const val MAX_SPEED_SCREENS = 12f
        /** Frame-to-frame jitter below this counts as "not moving". */
        const val STATIC_TOL_SCREENS = 0.006f
        /** A note must have travelled at least this far before it is trusted. */
        const val MIN_TRAVEL_SCREENS = 0.05f
        const val STATIC_HITS = 3
        const val SPEED_WINDOW = 15
        /** First sighting must be above this fraction of the way down to the hit line. */
        const val SPAWN_FRACTION = 0.55f
    }
}
