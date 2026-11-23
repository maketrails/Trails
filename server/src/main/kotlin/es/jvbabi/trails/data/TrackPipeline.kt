package es.jvbabi.trails.data

import es.jvbabi.trails.data.elevation.ElevationLookup
import es.jvbabi.trails.data.model.Movement
import kotlin.math.*
import kotlin.time.Instant

/**
 * The algorithm behind [TrailOptimizer]: turns raw positions into a track worth
 * drawing and tells how the device moved along it. Pure — no database, no state —
 * so the speed chart in the `tools` source set runs exactly what the server runs.
 *
 * Six stages, in this order:
 * 1. **Trust filter** — only positions below [MAX_ACCURACY_METERS] are used.
 *    Anything worse cannot be told apart from noise — unless the device moves
 *    fast enough that the error is small against the distance covered, see
 *    [MOVING_ACCURACY_RATIO].
 * 2. **Segmenting** — a recording pause longer than [SEGMENT_GAP_SECONDS]
 *    separates two independent stretches of movement. A spike right at such a
 *    gap is removed first, while the positions on both sides can still see it.
 * 3. **Stale repeat removal** — a fix reported again under a later timestamp
 *    while the device moves squeezes the next step into too little time.
 * 4. **Spike removal** — a run of up to [SPIKE_MAX_RUN] positions whose two
 *    neighbours are much closer to each other than to it is GPS noise: real
 *    movement continues in some direction, noise leaves and comes back. A
 *    sharp U-turn that the positions around it lead into and out of at the
 *    same pace stays. So does a detour only reachable at a speed or
 *    acceleration the movement around it does not explain.
 * 5. **Stationary collapse** — a cloud of positions that stays inside
 *    [STATIONARY_RADIUS_METERS] for at least [STATIONARY_MIN_SECONDS] becomes
 *    one accuracy weighted center, written twice so the pause keeps its
 *    duration instead of the device wandering around while it sits still.
 * 6. **Smoothing** — the positions in motion are fitted to a parabola through
 *    their neighbours in time, see [SMOOTHING_SIGMA_SECONDS]. Pauses and turns
 *    are left as they are, so a stop stays a stop instead of turning into a slow
 *    crawl and a corner is not cut. No position moves further than its own
 *    accuracy, see [SMOOTHING_MAX_SHIFT_METERS].
 *
 * The thresholds were tuned against a 79 day, 156k position export in the
 * `optimizer/` Python playground, where 40 % of the positions failed the trust
 * filter, 0.8 % were spikes and 29 % belonged to a pause.
 *
 * After smoothing, every segment is cut into legs at its pauses, and each leg is
 * classified as walking, bike or travel by its speed — see [classifyMovement] and
 * [smoothMovement]. Where the terrain elevation is known, the speed is read against
 * the gradient first, see [gradeAdjustedKmh]: a cyclist climbing at walking pace stays
 * a cyclist, one rolling downhill at car speed does not become a car.
 */
internal object TrackPipeline {
    const val MAX_ACCURACY_METERS = 20.0

    /**
     * A worse fix is still trusted while its accuracy stays below this share
     * of the distance between its two neighbours. On a highway those are a few
     * hundred metres apart, so ±45 m is plain to see; standing still, they are
     * not, and the fix stays out.
     */
    const val MOVING_ACCURACY_RATIO = 0.25

    /** Upper bound for [MOVING_ACCURACY_RATIO], however fast the device moves. */
    const val MAX_MOVING_ACCURACY_METERS = 75.0
    const val SEGMENT_GAP_SECONDS = 300.0

    /** Generous on purpose: the same pipeline has to survive planes. */
    const val MAX_SPEED_METERS_PER_SECOND = 100.0

    const val SPIKE_RETURN_RATIO = 0.35
    const val SPIKE_MIN_EXCURSION_METERS = 25.0
    const val SPIKE_PASSES = 4

    /**
     * How many consecutive positions may form one spike. A phone that loses its
     * fix tends to report two or three bad positions in a row before it is back;
     * a longer excursion has enough positions to be told from noise by its shape.
     */
    const val SPIKE_MAX_RUN = 3

    /**
     * A step into or out of an excursion still continues the track around it while
     * it turns by less than this. A U-turn is led into straight on and left the
     * same way; a spike leaves the track at an angle and comes back at another.
     */
    const val SPIKE_SUPPORT_MAX_TURN_DEGREES = 60.0

    /**
     * A step into or out of an excursion continues the track around it only while it
     * is at most this many times as fast as its neighbouring step — see
     * [SPIKE_SUPPORT_MIN_SPEED_METERS_PER_SECOND] for the floor that speed is
     * compared against.
     */
    const val SPIKE_SUPPORT_SPEED_FACTOR = 2.0

    /**
     * Floor for the neighbouring speed in [SPIKE_SUPPORT_SPEED_FACTOR]: standing
     * still, jitter of a few metres must not make any step look fast.
     */
    const val SPIKE_SUPPORT_MIN_SPEED_METERS_PER_SECOND = 1.0

    /**
     * Change of speed a device can plausibly build up between two fixes, in m/s².
     * A brisk car start manages about 2.5. A step faster than the speed around it
     * allows for — plus what its accuracy explains — is a jump, not a ride.
     */
    const val MAX_ACCELERATION_METERS_PER_SECOND_SQUARED = 2.5

    /**
     * Same order of magnitude as the accuracy we trust: inside it, noise
     * and movement cannot be told apart.
     */
    const val STATIONARY_RADIUS_METERS = 20.0

    /**
     * Three fixes ten seconds apart span twenty seconds — how a stop of about
     * half a minute, a traffic light, shows up. Smoothing would blur anything
     * shorter than a pause into the movement around it.
     */
    const val STATIONARY_MIN_SECONDS = 20.0
    const val STATIONARY_MIN_POINTS = 3

    /**
     * Width of the Gaussian that weights the neighbours of a moving position by
     * their distance in time. With fixes ten seconds apart, the direct
     * neighbours count 61 %, the next ones 14 % — enough to even out the
     * jitter between two fixes without cutting corners.
     */
    const val SMOOTHING_SIGMA_SECONDS = 10.0

    /** Neighbours further away than this count too little to be worth reading. */
    const val SMOOTHING_WINDOW_SECONDS = 2 * SMOOTHING_SIGMA_SECONDS

    /**
     * Upper bound for how far smoothing may move a position, which also never
     * moves one further than its own accuracy. Whatever the fit still gets wrong
     * in a bend stays within what the fix itself could be off.
     */
    const val SMOOTHING_MAX_SHIFT_METERS = 10.0

    /**
     * A position where the track turns by at least this much is a turn: smoothing
     * leaves it where it is and does not look past it, so the line fitted on one
     * side of a corner does not pull the other side inwards.
     */
    const val TURN_MIN_DEGREES = 35.0

    /**
     * The direction into and out of a position is read towards the nearest
     * neighbours at least this far away — or twice the position's accuracy, if
     * that is more — so jitter between closely spaced fixes does not read as a turn.
     */
    const val TURN_MIN_ARM_METERS = 10.0

    /**
     * A leg is judged by the speed it stays below for this share of its time,
     * not by its average or its peak: a cyclist waiting at a light or a car in
     * traffic would drag the average down, a single leftover jitter would push
     * the peak up.
     */
    const val MOVEMENT_SPEED_PERCENTILE = 0.85

    /**
     * Walking tops out around 7–8 km/h, a leg with one hurried stretch around
     * 13. A cyclist commuting in town rides 16–26 km/h.
     */
    const val WALKING_MAX_KMH = 14.0

    /** Faster than any everyday bike ride; cars and trains in town start at 35. */
    const val BIKE_MAX_KMH = 30.0

    /**
     * A step of at least [STATIONARY_MIN_SECONDS] slower than this counts as a
     * pause between two legs. Nobody walks this slowly while fixes keep coming
     * every ten seconds; a longer, slow step means the device stopped, and the
     * distance is just the last metres before and after.
     */
    const val PAUSE_MAX_KMH = 5.0

    /**
     * A long step slower than this share of the faster step next to it counts as a
     * pause too. The first fix after a stop often comes when the device is already
     * under way again: ninety seconds at a station and ten more at 60 km/h average
     * out at 6 km/h — too fast for [PAUSE_MAX_KMH], but a tenth of the train around it.
     * A walker whose phone reports seldom keeps the same pace across every step and
     * is not cut up.
     */
    const val PAUSE_SPEED_RATIO = 0.25

    /** A leg covering less than this is jitter around a spot, not a way somewhere. */
    const val MOVEMENT_MIN_DISTANCE_METERS = 50.0

    /**
     * Legs separated by a shorter pause belong to the same trip and may lend
     * each other their mode, see [smoothMovement]. A longer pause ends the trip.
     */
    const val TRIP_MAX_PAUSE_SECONDS = 300.0

    /**
     * How long a mode has to last within a trip to be believed. A shorter
     * stretch — a slow minute on the bike, a fast one on foot — takes the mode
     * of its neighbours instead.
     */
    const val WALKING_MIN_SECONDS = 300.0
    const val BIKE_MIN_SECONDS = 120.0
    const val TRAVEL_MIN_SECONDS = 120.0

    /**
     * How long a mode has to last to interrupt another one that resumes right
     * after it. Getting off a train, riding a bike and getting back on takes
     * longer than this; a train crawling between two stations at bike speed
     * does not.
     */
    const val INTERRUPTION_MIN_SECONDS = 300.0

    /**
     * A bus in town runs at bike speed, but not like a bike: it accelerates and
     * brakes again every few hundred metres, where a bike cruises. A slowdown is a
     * drop below [SLOWDOWN_RATIO] of the cruising speed after having been above
     * [SLOWDOWN_RECOVERY_RATIO] of it. Bike rides show 0.5–1 of them per km, a
     * city bus more than 2.
     */
    const val BIKE_MAX_SLOWDOWNS_PER_KM = 1.5
    const val SLOWDOWN_RATIO = 0.5
    const val SLOWDOWN_RECOVERY_RATIO = 0.75

    /** Below this, a handful of traffic lights decides — too few to count on. */
    const val SLOWDOWN_MIN_DISTANCE_METERS = 1_000.0

    /**
     * The gradient of a step is the slope of the terrain across this distance around
     * it. Elevation models are off by several metres from one pixel to the next; over
     * a few positions 15 m apart that would read as a wall, over 80 m it evens out.
     */
    const val GRADE_WINDOW_METERS = 80.0

    /**
     * Without this much track to measure across, the gradient is left at zero: the
     * elevation difference would be mostly noise.
     */
    const val GRADE_MIN_SPAN_METERS = 30.0

    /**
     * The first percent of every gradient is ignored: elevation noise, and too little
     * slope to change how anybody moves.
     */
    const val GRADE_DEADBAND = 0.01

    /** Gradients beyond this are elevation errors — a bridge, a tunnel, a cliff edge. */
    const val GRADE_MAX = 0.25

    /**
     * A cyclist as [gradeAdjustedKmh] models one: rider and bike, steady pedalling
     * power, rolling resistance and air drag (½ · air density · drag area of an upright
     * rider). On the flat that cruises at about 27 km/h.
     */
    const val CYCLIST_MASS_KG = 90.0
    const val CYCLIST_POWER_WATTS = 150.0
    const val CYCLIST_ROLLING_RESISTANCE = 0.006
    const val CYCLIST_DRAG = 0.25

    /**
     * Uphill, walking ends no lower than this. Walkers slow down on a climb too, to
     * 4–5 km/h on 6 %, while a cyclist rarely drops below 7; a climb ridden slower is
     * one pushed.
     */
    const val UPHILL_WALKING_MIN_KMH = 6.5

    /**
     * Downhill, bike speed ends no higher than this however steep it gets. Faster is a
     * car on a mountain road far more often than a racing cyclist.
     */
    const val DOWNHILL_BIKE_MAX_KMH = 60.0

    private const val GRAVITY = 9.81

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * A stretch between two pauses and how the device covered it. [measuredMode]
     * is what its own speed says, [mode] what is left after [smoothMovement].
     */
    data class Leg(
        val positions: List<Position>,
        val distanceMeters: Double,
        val speedKmh: Double,
        val measuredMode: Movement.Type,
        val mode: Movement.Type = measuredMode
    ) {
        val start get() = positions.first().timestamp
        val end get() = positions.last().timestamp
    }

    /**
     * One stretch of the derived track between two recording gaps, together with the
     * legs it is cut into, classified by their own speed only. Whether that holds is
     * decided in the context of the whole trip, see [MovementStream].
     */
    data class Segment(val positions: List<Position>, val legs: List<Leg>)

    /**
     * How [MovementStream.add] asks to store the movements: everything stored from
     * [replaceFrom] on gives way to [runs], one movement per run. Runs starting at or
     * after [openFrom] belong to a trip the next batch may still continue, so they are
     * provisional — stored, but replaced by the next call.
     */
    data class MovementUpdate(
        val replaceFrom: Instant?,
        val runs: List<List<Leg>>,
        val openFrom: Instant?,
    )

    /**
     * Follows the movements of one run across its batches.
     *
     * A batch boundary falls anywhere, also into the middle of a trip, and the smoothing
     * of a trip needs all of it: a short stretch between two stretches of the same mode
     * is only recognisable as an interruption once the second one is there. So the last
     * trip of every batch is kept back and smoothed again together with the next one,
     * and what was stored for it is replaced.
     *
     * The first batch has to start where a trip starts — see where the optimizer places
     * its lower bound.
     */
    class MovementStream {
        /** The legs of the trip the previous batch ended in, as measured. */
        private var open: List<Leg> = emptyList()

        fun add(legs: List<Leg>): MovementUpdate {
            val all = open + legs
            val replaceFrom = open.firstOrNull()?.start

            open = trips(all).lastOrNull().orEmpty()

            return MovementUpdate(
                replaceFrom = replaceFrom,
                runs = runs(smoothMovement(all)),
                openFrom = open.firstOrNull()?.start,
            )
        }
    }

    /**
     * One position on its way through the pipeline. Carries the columns that
     * are not part of the optimization so a derived position can keep the
     * bearing and battery state of the measurement it came from.
     */
    data class Position(
        val timestamp: Instant,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val bearing: Double,
        val bearingAccuracy: Double?,
        val batteryLevel: Float?,
        val batteryCharging: Boolean?
    )

    /**
     * Runs [positions] — one batch of raw measurements, oldest first — through all
     * stages and cuts every resulting segment into classified legs. Feed those to a
     * [MovementStream] to get the movements.
     */
    fun process(positions: List<Position>, elevation: ElevationLookup = ElevationLookup.NONE): List<Segment> = positions
        .let(::dropUntrusted)
        .let(::dropGapSpikes)
        .let(::splitSegments)
        .map { segment ->
            val track = smooth(collapseStationary(dropSpikes(dropStaleRepeats(segment))))
            Segment(track, classifiedLegs(track, elevation))
        }

    /**
     * Keeps the positions whose accuracy is good enough — absolutely, or relative
     * to how far the device moved around them.
     *
     * The movement is measured between the two raw neighbours, not from the
     * position itself, so its own error cannot make it look like movement. A
     * position at a batch edge or next to a recording pause has no such pair and
     * only passes the absolute limit.
     */
    private fun dropUntrusted(positions: List<Position>): List<Position> =
        positions.filterIndexed { index, position ->
            if (position.accuracy < MAX_ACCURACY_METERS) return@filterIndexed true

            val previous = positions.getOrNull(index - 1) ?: return@filterIndexed false
            val following = positions.getOrNull(index + 1) ?: return@filterIndexed false

            if (seconds(previous, following) > SEGMENT_GAP_SECONDS) return@filterIndexed false

            val limit = min(MAX_MOVING_ACCURACY_METERS, MOVING_ACCURACY_RATIO * distance(previous, following))
            position.accuracy < limit
        }

    /** Cuts the stream wherever the device stopped reporting for a while. */
    private fun splitSegments(positions: List<Position>): List<List<Position>> {
        if (positions.isEmpty()) return emptyList()

        val segments = mutableListOf(mutableListOf(positions.first()))

        for ((previous, current) in positions.zipWithNext()) {
            if (seconds(previous, current) > SEGMENT_GAP_SECONDS) {
                segments += mutableListOf(current)
            } else {
                segments.last() += current
            }
        }

        return segments
    }

    /**
     * Removes positions that repeat the previous fix under a later timestamp while
     * the device is moving.
     *
     * Without a fresh fix the phone reports its last known location again, stamped
     * with the time of the report. The distance to the next real fix is then covered
     * in the few seconds left instead of the whole interval, which shows up as a
     * speed several times the real one. Standing still, a repeat is indistinguishable
     * from a real fix and may mark the end of a pause, so it is kept.
     */
    private fun dropStaleRepeats(segment: List<Position>): List<Position> {
        if (segment.size < 3) return segment

        val survivors = mutableListOf(segment.first())

        for (index in 1 until segment.lastIndex) {
            val previous = survivors.last()
            val current = segment[index]
            val following = segment[index + 1]

            val repeats = current.latitude == previous.latitude && current.longitude == previous.longitude
            val moving = distance(previous, following) >
                    max(STATIONARY_RADIUS_METERS, previous.accuracy + following.accuracy)

            if (repeats && moving) continue

            survivors += current
        }

        survivors += segment.last()
        return survivors
    }

    /**
     * Removes spikes right before or after a recording gap, which [dropSpikes] cannot
     * see: it works on one segment at a time, and the position the spike returns to
     * lies on the other side of the gap.
     *
     * A phone that stands still and stops reporting often sends one stray fix on the
     * way out, then resumes where it was. Only the side of the gap is used to check
     * the return — a position between two gaps is a segment of its own and left alone.
     */
    private fun dropGapSpikes(positions: List<Position>): List<Position> =
        positions.filterIndexed { index, position ->
            val previous = positions.getOrNull(index - 1) ?: return@filterIndexed true
            val following = positions.getOrNull(index + 1) ?: return@filterIndexed true

            val gapBefore = seconds(previous, position) > SEGMENT_GAP_SECONDS
            val gapAfter = seconds(position, following) > SEGMENT_GAP_SECONDS
            if (gapBefore == gapAfter) return@filterIndexed true

            !isSpike(previous, position, following)
        }

    /**
     * Whether [current] leaves the way from [previous] to [following] and comes
     * back, without any context to tell it from a U-turn — see [isNeedle].
     */
    private fun isSpike(previous: Position, current: Position, following: Position): Boolean =
        isNeedle(previous, listOf(current), following, before = null, after = null)

    /**
     * Whether [run] leaves the way from [previous] to [following] and comes back:
     * the two are much closer to each other than the way through [run] is long,
     * and [run] reaches further out than their accuracy explains.
     *
     * A real U-turn has that shape too. It is told apart by the positions around
     * it: [before] leads into it and [after] out of it at the same pace and in the
     * same direction, see [continues]. Without both of them, the shape decides.
     */
    private fun isNeedle(
        previous: Position,
        run: List<Position>,
        following: Position,
        before: Position?,
        after: Position?,
    ): Boolean {
        val path = listOf(previous) + run + following
        val length = path.zipWithNext().sumOf { (first, second) -> distance(first, second) }
        val skipping = distance(previous, following)

        val excursion = run.maxOf { min(distance(previous, it), distance(it, following)) }
        val noise = previous.accuracy + run.maxOf { it.accuracy }

        val returns = skipping <= SPIKE_RETURN_RATIO * length
        val farEnough = excursion >= max(SPIKE_MIN_EXCURSION_METERS, noise)
        if (!returns || !farEnough) return false

        val ledInto = before != null && continues(before, previous, previous, run.first())
        val ledOutOf = after != null && continues(following, after, run.last(), following)

        return !(ledInto && ledOutOf)
    }

    /**
     * Whether the step from [stepFrom] to [stepTo] continues the step from
     * [contextFrom] to [contextTo] next to it: about the same direction, see
     * [SPIKE_SUPPORT_MAX_TURN_DEGREES], and not much faster, see
     * [SPIKE_SUPPORT_SPEED_FACTOR].
     */
    private fun continues(contextFrom: Position, contextTo: Position, stepFrom: Position, stepTo: Position): Boolean {
        val contextSpeed = speedMetersPerSecond(contextFrom, contextTo) ?: return false
        val stepSpeed = speedMetersPerSecond(stepFrom, stepTo) ?: return false

        val fastEnough = stepSpeed <=
                SPIKE_SUPPORT_SPEED_FACTOR * max(contextSpeed, SPIKE_SUPPORT_MIN_SPEED_METERS_PER_SECOND)

        return fastEnough && turnDegrees(contextFrom, contextTo, stepFrom, stepTo) <= SPIKE_SUPPORT_MAX_TURN_DEGREES
    }

    /**
     * Whether the way from [previous] through [run] to [following] takes a step
     * faster than the movement around it allows for, while going from [previous]
     * to [following] directly does not — see
     * [MAX_ACCELERATION_METERS_PER_SECOND_SQUARED].
     *
     * The movement around it is the faster of the steps [before] and [after] the
     * stretch. Without both of them only [MAX_SPEED_METERS_PER_SECOND] counts:
     * an accelerating car at the edge of a batch would look like a jump.
     */
    private fun isJump(
        previous: Position,
        run: List<Position>,
        following: Position,
        before: Position?,
        after: Position?,
    ): Boolean {
        val around = if (before != null && after != null) {
            max(
                speedMetersPerSecond(before, previous) ?: 0.0,
                speedMetersPerSecond(following, after) ?: 0.0,
            )
        } else {
            null
        }

        fun implausible(first: Position, second: Position): Boolean {
            val seconds = seconds(first, second)
            if (seconds <= 0) return true

            val limit = if (around == null) MAX_SPEED_METERS_PER_SECOND else min(
                MAX_SPEED_METERS_PER_SECOND,
                around + MAX_ACCELERATION_METERS_PER_SECOND_SQUARED * seconds / 2 +
                        (first.accuracy + second.accuracy) / seconds
            )

            return distance(first, second) / seconds > limit
        }

        if (implausible(previous, following)) return false

        return (listOf(previous) + run + following).zipWithNext().any { (first, second) -> implausible(first, second) }
    }

    /**
     * Removes positions that jump away and immediately come back, alone or in a
     * run of up to [SPIKE_MAX_RUN].
     *
     * Real movement continues in some direction. GPS noise around a spot
     * leaves the track and returns to where it came from, so the two
     * neighbours of a spike are close to each other while both are far away
     * from the positions in between — see [isNeedle]. A detour that only a jump
     * could have made goes too, see [isJump].
     *
     * The shortest run that explains the excursion is removed, so a single bad
     * position never takes a good neighbour with it.
     */
    private fun dropSpikes(segment: List<Position>): List<Position> {
        var kept = segment

        repeat(SPIKE_PASSES) {
            if (kept.size < 3) return kept

            /*
             * The edges have only one neighbour to be checked against, so the
             * two positions next to them decide instead. A segment typically
             * opens with the last fix from before the gap, reported again when
             * recording resumes somewhere else entirely.
             */
            val start = if (unreachable(kept[0], kept[1]) && !unreachable(kept[1], kept[2])) 1 else 0

            val survivors = mutableListOf(kept[start])
            var removed = start

            var index = start + 1

            while (index < kept.lastIndex) {
                val spike = spikeLength(survivors, kept, index)

                if (spike > 0) {
                    removed += spike
                    index += spike
                } else {
                    survivors += kept[index]
                    index++
                }
            }

            val last = kept.last()
            val lastJumps = survivors.size >= 2 &&
                    unreachable(survivors.last(), last) &&
                    !unreachable(survivors[survivors.lastIndex - 1], survivors.last())

            if (lastJumps) removed++ else survivors += last
            kept = survivors

            if (removed == 0) return kept
        }

        return kept
    }

    /**
     * How many positions from `kept[index]` on form a spike, 0 if none do. The
     * position before them is the last one that survived, the one after them is
     * the next one in [kept]; one more on either side tells a spike from a U-turn.
     * The segment's last position is never part of a spike here, see [dropSpikes].
     */
    private fun spikeLength(survivors: List<Position>, kept: List<Position>, index: Int): Int {
        val previous = survivors.last()
        val before = survivors.getOrNull(survivors.lastIndex - 1)

        for (length in 1..SPIKE_MAX_RUN) {
            val followingIndex = index + length
            if (followingIndex > kept.lastIndex) break

            val run = kept.subList(index, followingIndex)
            val following = kept[followingIndex]
            val after = kept.getOrNull(followingIndex + 1)

            if (isNeedle(previous, run, following, before, after) || isJump(previous, run, following, before, after)) {
                return length
            }
        }

        return 0
    }

    /**
     * Replaces a jitter cloud around one spot with a single position.
     *
     * The cloud is kept as two identical positions, one at the arrival and one
     * at the departure timestamp, so the pause stays visible in the track.
     */
    private fun collapseStationary(segment: List<Position>): List<Position> {
        val output = mutableListOf<Position>()
        var index = 0

        while (index < segment.size) {
            val anchor = segment[index]

            val cluster = mutableListOf(anchor)
            var center = anchor.latitude to anchor.longitude

            var follower = index + 1

            while (follower < segment.size) {
                val candidate = segment[follower]

                /*
                 * Bound the cloud against its anchor as well: without that a
                 * slow walk drags the center along and the cluster never ends.
                 */
                val toAnchor = distance(anchor, candidate)
                val toCenter = distance(center.first, center.second, candidate.latitude, candidate.longitude)

                if (max(toAnchor, toCenter) > STATIONARY_RADIUS_METERS) break

                cluster += candidate
                center = weightedCenter(cluster)
                follower++
            }

            val duration = seconds(cluster.first(), cluster.last())
            val isPause = cluster.size >= STATIONARY_MIN_POINTS && duration >= STATIONARY_MIN_SECONDS

            if (isPause) {
                val accuracy = cluster.minOf { it.accuracy }

                // Arrival and departure keep their own battery state; only the
                // position becomes the shared center.
                output += cluster.first().copy(
                    latitude = center.first,
                    longitude = center.second,
                    accuracy = accuracy
                )
                output += cluster.last().copy(
                    latitude = center.first,
                    longitude = center.second,
                    accuracy = accuracy
                )
            } else {
                output += cluster
            }

            index = follower
        }

        return output
    }

    /**
     * Moves every moving position onto a curve fitted through its neighbours in
     * time, weighted by [SMOOTHING_SIGMA_SECONDS] and by accuracy, see [LocalFit].
     *
     * Positions that share their coordinates with a neighbour are pinned: that is
     * the arrival and departure pair [collapseStationary] leaves behind, or a device
     * standing still. They are neither moved nor looked past, so the movement on
     * one side of a stop does not leak into the other and the stop keeps a speed of
     * zero. The segment edges are pinned as well — with neighbours on one side only,
     * the average would drag them into the segment. So are turns, see [isTurn]: a
     * curve fitted across a corner would cut it.
     *
     * What is left of a bend is bounded too: no position moves further than its
     * accuracy or [SMOOTHING_MAX_SHIFT_METERS].
     */
    private fun smooth(segment: List<Position>): List<Position> {
        fun samePlace(first: Position, second: Position) =
            first.latitude == second.latitude && first.longitude == second.longitude

        val pinned = segment.indices.map { index ->
            index == 0 || index == segment.lastIndex ||
                    samePlace(segment[index - 1], segment[index]) ||
                    samePlace(segment[index], segment[index + 1]) ||
                    isTurn(segment, index)
        }

        return segment.mapIndexed { index, position ->
            if (pinned[index]) return@mapIndexed position

            /*
             * A weighted mean would be pulled towards whichever side has more
             * neighbours — a fix missing on one side shifts a fast device by a
             * hundred metres. Fitting a curve through the neighbours and reading it
             * at this position's time stays on the track however they are spread.
             * A parabola rather than a straight line: a line through a bend sits
             * on its inside, a parabola follows it. Offsets from the position keep
             * the sums small.
             */
            val fit = LocalFit()

            fun include(neighbour: Position) {
                val time = seconds(position, neighbour)
                val weight = exp(-time.pow(2) / (2 * SMOOTHING_SIGMA_SECONDS.pow(2))) /
                        max(neighbour.accuracy, 1.0).pow(2)

                fit.add(
                    time = time,
                    weight = weight,
                    latitudeOffset = neighbour.latitude - position.latitude,
                    longitudeOffset = neighbour.longitude - position.longitude,
                )
            }

            include(position)

            for (direction in listOf(-1, 1)) {
                var neighbour = index + direction

                while (neighbour in segment.indices &&
                    abs(seconds(segment[neighbour], position)) <= SMOOTHING_WINDOW_SECONDS
                ) {
                    include(segment[neighbour])
                    if (pinned[neighbour]) break
                    neighbour += direction
                }
            }

            // Null without a neighbour: nothing to fit, the position stays as it is.
            val (latitudeShift, longitudeShift) = fit.valueAtZero() ?: return@mapIndexed position

            val shift = distance(
                position.latitude, position.longitude,
                position.latitude + latitudeShift, position.longitude + longitudeShift
            )
            val maxShift = min(position.accuracy, SMOOTHING_MAX_SHIFT_METERS)
            val scale = if (shift > maxShift) maxShift / shift else 1.0

            position.copy(
                latitude = position.latitude + scale * latitudeShift,
                longitude = position.longitude + scale * longitudeShift
            )
        }
    }

    /**
     * Weighted least squares fit of latitude and longitude offsets over time, for
     * [smooth]: a parabola where the neighbours pin one down, a straight line where
     * they only span two distinct times.
     */
    private class LocalFit {
        /** `moments[k]` is the weighted sum of `time^k`. */
        private val moments = DoubleArray(5)
        private val latitude = DoubleArray(3)
        private val longitude = DoubleArray(3)

        fun add(time: Double, weight: Double, latitudeOffset: Double, longitudeOffset: Double) {
            var power = weight

            for (k in 0..4) {
                moments[k] += power
                if (k < 3) {
                    latitude[k] += power * latitudeOffset
                    longitude[k] += power * longitudeOffset
                }
                power *= time
            }
        }

        /** The fitted offsets at time zero, or null if nothing can be fitted. */
        fun valueAtZero(): Pair<Double, Double>? = quadratic() ?: linear()

        private fun quadratic(): Pair<Double, Double>? {
            val (s0, s1, s2, s3, s4) = moments

            // Cramer's rule for the intercept: the system's determinant with its
            // first column replaced by (a, b, c).
            fun determinant(a: Double, b: Double, c: Double) =
                a * (s2 * s4 - s3 * s3) - s1 * (b * s4 - s3 * c) + s2 * (b * s3 - s2 * c)

            val determinant = determinant(s0, s1, s2)

            // Fewer than three distinct times: no parabola, only a line.
            if (determinant <= QUADRATIC_MIN_CONDITION * s0 * s2 * s4) return null

            return determinant(latitude[0], latitude[1], latitude[2]) / determinant to
                    determinant(longitude[0], longitude[1], longitude[2]) / determinant
        }

        private fun linear(): Pair<Double, Double>? {
            val (s0, s1, s2) = moments
            val determinant = s0 * s2 - s1 * s1
            if (determinant <= 0) return null

            return (s2 * latitude[0] - s1 * latitude[1]) / determinant to
                    (s2 * longitude[0] - s1 * longitude[1]) / determinant
        }

        private companion object {
            /** Below this share of its scale the parabola's system counts as singular. */
            const val QUADRATIC_MIN_CONDITION = 1e-6
        }
    }

    /**
     * Whether the track turns at `segment[index]` by at least [TURN_MIN_DEGREES].
     *
     * The direction in is read from the nearest earlier position at least an arm's
     * length away, the direction out towards the nearest later one, see
     * [TURN_MIN_ARM_METERS]; only positions within [SMOOTHING_WINDOW_SECONDS] are
     * looked at, as smoothing would not reach further either. Without such a
     * position on both sides, the movement is too small against the noise to tell.
     */
    private fun isTurn(segment: List<Position>, index: Int): Boolean {
        val position = segment[index]
        val arm = max(TURN_MIN_ARM_METERS, 2 * position.accuracy)

        fun reach(direction: Int): Position? {
            var neighbour = index + direction

            while (neighbour in segment.indices &&
                abs(seconds(segment[neighbour], position)) <= SMOOTHING_WINDOW_SECONDS
            ) {
                if (distance(segment[neighbour], position) >= arm) return segment[neighbour]
                neighbour += direction
            }

            return null
        }

        val earlier = reach(-1) ?: return false
        val later = reach(1) ?: return false

        return turnDegrees(earlier, position, position, later) >= TURN_MIN_DEGREES
    }

    /**
     * By how many degrees, 0 to 180, the direction from [secondFrom] to [secondTo]
     * differs from the one from [firstFrom] to [firstTo].
     */
    private fun turnDegrees(firstFrom: Position, firstTo: Position, secondFrom: Position, secondTo: Position): Double {
        val difference = abs(heading(secondFrom, secondTo) - heading(firstFrom, firstTo)) % 360
        return if (difference > 180) 360 - difference else difference
    }

    /** Direction from [from] to [to] in degrees, on a plane — fine across a few hundred metres. */
    private fun heading(from: Position, to: Position): Double {
        val north = to.latitude - from.latitude
        val east = (to.longitude - from.longitude) * cos(Math.toRadians((from.latitude + to.latitude) / 2))
        return Math.toDegrees(atan2(east, north))
    }

    /**
     * The legs of [segment] that cover enough ground to be judged, see [classifyMovement].
     *
     * Every step's speed is read against its gradient first, see [gradeAdjustedKmh], and
     * the leg is classified by the percentile of those. A leg can climb and descend, so
     * this happens step by step rather than once per leg.
     */
    private fun classifiedLegs(segment: List<Position>, elevation: ElevationLookup): List<Leg> {
        val grades = stepGrades(segment, elevation)

        return legs(segment).mapNotNull { range ->
            val positions = segment.subList(range.first, range.last + 1)

            val distance = positions.zipWithNext().sumOf { (first, second) -> distance(first, second) }
            if (distance < MOVEMENT_MIN_DISTANCE_METERS) return@mapNotNull null

            val speed = speedPercentileKmh(positions, MOVEMENT_SPEED_PERCENTILE) ?: return@mapNotNull null

            val adjusted = (range.first until range.last).mapNotNull { step ->
                val seconds = seconds(segment[step], segment[step + 1])
                if (seconds <= 0) null
                else gradeAdjustedKmh(distance(segment[step], segment[step + 1]) / seconds * 3.6, grades[step]) to seconds
            }
            val adjustedSpeed = percentile(adjusted, MOVEMENT_SPEED_PERCENTILE) ?: return@mapNotNull null

            Leg(positions, distance, speed, classifyMovement(adjustedSpeed))
        }
    }

    /**
     * The signed gradient of every step of [segment] — `grades[i]` for the step from
     * position `i` to `i + 1`, positive uphill — measured across [GRADE_WINDOW_METERS]
     * of track around the step's middle.
     *
     * All zero, the flat track the classification assumed before elevation, as soon as
     * the elevation of one position is unknown: a gradient made up from the positions
     * around a gap would be a guess.
     */
    private fun stepGrades(segment: List<Position>, elevation: ElevationLookup): DoubleArray {
        val grades = DoubleArray(max(segment.size - 1, 0))
        if (segment.size < 2) return grades

        val elevations = segment.map { elevation.elevationAt(it.latitude, it.longitude) ?: return grades }

        val along = DoubleArray(segment.size)
        for (index in 1 until segment.size) along[index] = along[index - 1] + distance(segment[index - 1], segment[index])

        /** The elevation [meters] along the track, linear between two positions. */
        fun elevationAlong(meters: Double): Double {
            val target = meters.coerceIn(0.0, along.last())
            val found = along.binarySearch(target)
            val index = (if (found >= 0) found else -found - 2).coerceIn(0, segment.size - 2)

            val span = along[index + 1] - along[index]
            val fraction = if (span <= 0) 0.0 else (target - along[index]) / span
            return elevations[index] + fraction * (elevations[index + 1] - elevations[index])
        }

        for (step in grades.indices) {
            val middle = (along[step] + along[step + 1]) / 2
            val from = max(0.0, middle - GRADE_WINDOW_METERS / 2)
            val to = min(along.last(), middle + GRADE_WINDOW_METERS / 2)
            if (to - from < GRADE_MIN_SPAN_METERS) continue

            grades[step] = ((elevationAlong(to) - elevationAlong(from)) / (to - from)).coerceIn(-GRADE_MAX, GRADE_MAX)
        }

        return grades
    }

    /**
     * What a speed of [kmh] on a gradient of [grade] amounts to on the flat, as far as
     * [classifyMovement] is concerned: the scale is stretched so that the speed limits of
     * walking and cycling at this gradient — [walkingMaxKmh] and [bikeMaxKmh] — land on
     * [WALKING_MAX_KMH] and [BIKE_MAX_KMH]. Anything faster is shifted by the same amount
     * the bike limit was. On the flat, and without elevation, nothing changes.
     *
     * Speed alone tells walking from cycling on the flat because a cyclist is three to
     * four times as fast. Uphill a cyclist loses far more of that speed than a walker
     * does, downhill a cyclist gains speed a walker does not, and the limits move with it.
     */
    private fun gradeAdjustedKmh(kmh: Double, grade: Double): Double {
        if (effectiveGrade(grade) == 0.0) return kmh

        val walkingMax = walkingMaxKmh(grade)
        val bikeMax = bikeMaxKmh(grade)

        return when {
            kmh <= walkingMax -> kmh * WALKING_MAX_KMH / walkingMax
            kmh <= bikeMax -> WALKING_MAX_KMH + (kmh - walkingMax) / (bikeMax - walkingMax) * (BIKE_MAX_KMH - WALKING_MAX_KMH)
            else -> BIKE_MAX_KMH + (kmh - bikeMax)
        }
    }

    /**
     * Where walking ends on [grade]. Uphill, [WALKING_MAX_KMH] shrinks the way a cyclist's
     * speed does at the same power — see [cyclingKmh] — but not below
     * [UPHILL_WALKING_MIN_KMH]. Downhill a walker is no faster, so nothing changes.
     */
    private fun walkingMaxKmh(grade: Double): Double {
        val climb = effectiveGrade(grade)
        if (climb <= 0) return WALKING_MAX_KMH

        return max(UPHILL_WALKING_MIN_KMH, WALKING_MAX_KMH * cyclingKmh(climb) / flatCyclingKmh)
    }

    /**
     * Where cycling ends on [grade]. Downhill it is the speed a cyclist reaches just
     * rolling, without pedalling — where gravity and drag cancel out — if that exceeds
     * [BIKE_MAX_KMH], capped at [DOWNHILL_BIKE_MAX_KMH]. An e-bike keeps its speed
     * uphill, so the limit does not drop there.
     */
    private fun bikeMaxKmh(grade: Double): Double {
        val descent = -effectiveGrade(grade)
        if (descent <= CYCLIST_ROLLING_RESISTANCE) return BIKE_MAX_KMH

        val rolling = sqrt(CYCLIST_MASS_KG * GRAVITY * (descent - CYCLIST_ROLLING_RESISTANCE) / CYCLIST_DRAG) * 3.6
        return rolling.coerceIn(BIKE_MAX_KMH, DOWNHILL_BIKE_MAX_KMH)
    }

    /** [grade] without its first [GRADE_DEADBAND]. */
    private fun effectiveGrade(grade: Double): Double = sign(grade) * max(0.0, abs(grade) - GRADE_DEADBAND)

    /**
     * How fast the modelled cyclist rides up [grade] at [CYCLIST_POWER_WATTS]: the speed
     * at which climbing, rolling resistance and drag take up exactly that power.
     */
    private fun cyclingKmh(grade: Double): Double {
        fun power(metersPerSecond: Double) =
            CYCLIST_MASS_KG * GRAVITY * (grade + CYCLIST_ROLLING_RESISTANCE) * metersPerSecond +
                    CYCLIST_DRAG * metersPerSecond.pow(3)

        // The power grows with the speed, so halving the interval finds it.
        var low = 0.0
        var high = 30.0
        repeat(50) {
            val middle = (low + high) / 2
            if (power(middle) < CYCLIST_POWER_WATTS) low = middle else high = middle
        }

        return (low + high) / 2 * 3.6
    }

    /** [cyclingKmh] on the flat, the reference [walkingMaxKmh] scales against. */
    private val flatCyclingKmh by lazy { cyclingKmh(0.0) }

    /**
     * Irons out modes that do not last: within a trip, a stretch of the same mode
     * shorter than [minSeconds] takes the mode of its longer neighbour.
     * Between two stretches of the same other mode it has to last at least
     * [INTERRUPTION_MIN_SECONDS] — see there. A bike stretch in stop-and-go
     * traffic becomes travel, see [BIKE_MAX_SLOWDOWNS_PER_KM].
     *
     * The shortest such stretch goes first, and the stretches are recomputed after
     * every change: a blip merged into its surroundings can make them long enough to
     * stand. A trip that consists of a single short stretch keeps it — there is
     * nothing to take a mode from.
     */
    private fun smoothMovement(legs: List<Leg>): List<Leg> = trips(legs).flatMap { trip ->
        val modes = trip.map { it.measuredMode }.toMutableList()

        // Every pass turns at least one bike stretch into travel, so there cannot be
        // more passes than legs — the bound only guards against the two rules
        // handing a stretch back and forth.
        for (pass in 0..trip.size) {
            smoothRuns(trip, modes)

            // A bike stretch in stop-and-go is a bus; once it is, its neighbours
            // may have to follow, so smoothing starts over.
            val stopAndGo = runRanges(modes).filter { run ->
                modes[run.first] == Movement.Type.Bike &&
                        (slowdownsPerKm(trip.slice(run)) ?: 0.0) > BIKE_MAX_SLOWDOWNS_PER_KM
            }
            if (stopAndGo.isEmpty()) break

            for (run in stopAndGo) for (index in run) modes[index] = Movement.Type.Travel()
        }

        trip.mapIndexed { index, leg -> leg.copy(mode = modes[index]) }
    }

    /** Merges the stretches of [modes] that are too short, see [smoothMovement]. */
    private fun smoothRuns(trip: List<Leg>, modes: MutableList<Movement.Type>) {
        fun duration(run: IntRange) = seconds(trip[run.first].positions.first(), trip[run.last].positions.last())

        while (true) {
            val runs = runRanges(modes)
            if (runs.size < 2) break

            fun minSeconds(index: Int): Double {
                val own = minSeconds(modes[runs[index].first])
                val before = runs.getOrNull(index - 1) ?: return own
                val after = runs.getOrNull(index + 1) ?: return own

                return if (modes[before.first] == modes[after.first]) max(own, INTERRUPTION_MIN_SECONDS) else own
            }

            val shortest = runs.indices
                .filter { index -> duration(runs[index]) < minSeconds(index) }
                .minByOrNull { index -> duration(runs[index]) }
                ?: break

            val neighbour = listOfNotNull(runs.getOrNull(shortest - 1), runs.getOrNull(shortest + 1))
                .maxBy(::duration)

            for (index in runs[shortest]) modes[index] = modes[neighbour.first]
        }
    }

    /**
     * How often the speed on [legs] drops well below its cruising speed, per km —
     * see [BIKE_MAX_SLOWDOWNS_PER_KM]. The pauses between the legs count as the
     * drops they are. Null for a stretch too short to tell.
     */
    private fun slowdownsPerKm(legs: List<Leg>): Double? {
        val length = legs.sumOf { it.distanceMeters }
        if (length < SLOWDOWN_MIN_DISTANCE_METERS) return null

        val positions = legs.flatMap { it.positions }
        val cruising = speedPercentileKmh(positions, MOVEMENT_SPEED_PERCENTILE) ?: return null

        var slowdowns = 0
        var cruisingSeen = false

        for ((first, second) in positions.zipWithNext()) {
            val seconds = seconds(first, second)
            if (seconds <= 0) continue

            val speed = distance(first, second) / seconds * 3.6

            if (speed >= SLOWDOWN_RECOVERY_RATIO * cruising) {
                cruisingSeen = true
            } else if (speed < SLOWDOWN_RATIO * cruising && cruisingSeen) {
                slowdowns++
                cruisingSeen = false
            }
        }

        return slowdowns / (length / 1000)
    }

    /** Splits [legs] wherever the pause between two of them exceeds [TRIP_MAX_PAUSE_SECONDS]. */
    private fun trips(legs: List<Leg>): List<List<Leg>> {
        val trips = mutableListOf<MutableList<Leg>>()

        for (leg in legs) {
            val previous = trips.lastOrNull()?.last()

            if (previous == null || seconds(previous.positions.last(), leg.positions.first()) > TRIP_MAX_PAUSE_SECONDS) {
                trips += mutableListOf(leg)
            } else {
                trips.last() += leg
            }
        }

        return trips
    }

    /** The index ranges of [modes] that hold the same mode. */
    private fun runRanges(modes: List<Movement.Type>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = 0

        for (index in 1..modes.size) {
            if (index == modes.size || modes[index] != modes[start]) {
                runs += start until index
                start = index
            }
        }

        return runs
    }

    /** Consecutive legs of the same trip and mode. */
    fun runs(legs: List<Leg>): List<List<Leg>> = trips(legs).flatMap { trip ->
        runRanges(trip.map { it.mode }).map { run -> trip.slice(run) }
    }

    /**
     * The index ranges of the stretches of [segment] between two pauses, each with at
     * least two positions.
     *
     * A pause is either the arrival and departure pair [collapseStationary] leaves
     * behind or a single step that is long and slow, see [isPause]: a phone that
     * stands still often stops delivering fixes altogether, so a stop at a station
     * or a traffic light never gathers the positions a collapse needs.
     */
    private fun legs(segment: List<Position>): List<IntRange> {
        val legs = mutableListOf<IntRange>()
        var start = 0

        for (index in 1..segment.size) {
            if (index == segment.size || isPause(segment, index)) {
                if (index - start >= 2) legs += start until index
                start = index
            }
        }

        return legs
    }

    /**
     * Whether the step into `segment[index]` is a pause between two legs: the arrival and
     * departure pair [collapseStationary] leaves behind, or a long step that is slow —
     * slow outright, see [PAUSE_MAX_KMH], or slow against the steps around it, see
     * [PAUSE_SPEED_RATIO].
     */
    private fun isPause(segment: List<Position>, index: Int): Boolean {
        val previous = segment[index - 1]
        val position = segment[index]

        if (previous.latitude == position.latitude && previous.longitude == position.longitude) return true
        if (seconds(previous, position) < STATIONARY_MIN_SECONDS) return false

        val speed = speedKmh(previous, position) ?: return false
        if (speed <= PAUSE_MAX_KMH) return true

        val around = listOfNotNull(
            segment.getOrNull(index - 2)?.let { speedKmh(it, previous) },
            segment.getOrNull(index + 1)?.let { speedKmh(position, it) },
        ).maxOrNull() ?: return false

        return speed <= PAUSE_SPEED_RATIO * around
    }

    /**
     * The speed [leg] stays below for [percentile] of its time. Weighted by time
     * rather than by step, so a burst of closely spaced fixes does not count more
     * than the same stretch recorded sparsely. Null if no time passes on the leg.
     */
    private fun speedPercentileKmh(leg: List<Position>, percentile: Double): Double? = percentile(
        leg.zipWithNext().mapNotNull { (first, second) ->
            val seconds = seconds(first, second)
            if (seconds <= 0) null else distance(first, second) / seconds * 3.6 to seconds
        },
        percentile,
    )

    /**
     * The value that [steps] — pairs of a value and how many seconds it lasted — stay
     * below for [percentile] of their time, or null if no time passes.
     */
    private fun percentile(steps: List<Pair<Double, Double>>, percentile: Double): Double? {
        val sorted = steps.sortedBy { (value, _) -> value }

        val total = sorted.sumOf { (_, seconds) -> seconds }
        if (total <= 0) return null

        var covered = 0.0
        for ((value, seconds) in sorted) {
            covered += seconds
            if (covered >= percentile * total) return value
        }

        return sorted.last().first
    }

    private fun classifyMovement(speedKmh: Double): Movement.Type = when {
        speedKmh <= WALKING_MAX_KMH -> Movement.Type.Walking
        speedKmh <= BIKE_MAX_KMH -> Movement.Type.Bike
        else -> Movement.Type.Travel()
    }

    /** How long [type] has to last within a trip to be believed, see [WALKING_MIN_SECONDS]. */
    private fun minSeconds(type: Movement.Type): Double = when (type) {
        Movement.Type.Walking -> WALKING_MIN_SECONDS
        Movement.Type.Bike -> BIKE_MIN_SECONDS
        is Movement.Type.Travel -> TRAVEL_MIN_SECONDS
    }

    /** Accuracy weighted mean position — a better fix counts more. */
    private fun weightedCenter(positions: List<Position>): Pair<Double, Double> {
        var latitude = 0.0
        var longitude = 0.0
        var weightSum = 0.0

        for (position in positions) {
            val weight = 1.0 / max(position.accuracy, 1.0).pow(2)

            latitude += position.latitude * weight
            longitude += position.longitude * weight
            weightSum += weight
        }

        return latitude / weightSum to longitude / weightSum
    }

    private fun speedMetersPerSecond(first: Position, second: Position): Double? {
        val seconds = seconds(first, second)
        if (seconds <= 0) return null

        return distance(first, second) / seconds
    }

    private fun unreachable(first: Position, second: Position): Boolean {
        val seconds = seconds(first, second)

        if (seconds <= 0) return true

        return distance(first, second) / seconds > MAX_SPEED_METERS_PER_SECOND
    }

    private fun seconds(first: Position, second: Position): Double =
        (second.timestamp - first.timestamp).inWholeMilliseconds / 1000.0

    private fun distance(first: Position, second: Position): Double =
        distance(first.latitude, first.longitude, second.latitude, second.longitude)

    fun distance(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val deltaLatitude = Math.toRadians(latitude2 - latitude1)
        val deltaLongitude = Math.toRadians(longitude2 - longitude1)

        val a = sin(deltaLatitude / 2).pow(2) +
                cos(Math.toRadians(latitude1)) *
                cos(Math.toRadians(latitude2)) *
                sin(deltaLongitude / 2).pow(2)

        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** The speed between two positions in km/h, or null if [second] is not later than [first]. */
    fun speedKmh(first: Position, second: Position): Double? {
        val seconds = seconds(first, second)
        if (seconds <= 0) return null

        return distance(first, second) / seconds * 3.6
    }
}
