package es.jvbabi.trails.data

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
 * 4. **Spike removal** — a position whose two neighbours are much closer to
 *    each other than to it is GPS noise: real movement continues in some
 *    direction, noise leaves and comes back.
 * 5. **Stationary collapse** — a cloud of positions that stays inside
 *    [STATIONARY_RADIUS_METERS] for at least [STATIONARY_MIN_SECONDS] becomes
 *    one accuracy weighted center, written twice so the pause keeps its
 *    duration instead of the device wandering around while it sits still.
 * 6. **Smoothing** — the positions in motion are fitted to a line through
 *    their neighbours in time, see [SMOOTHING_SIGMA_SECONDS]. Pauses are left as
 *    they are, so a stop stays a stop instead of turning into a slow crawl.
 *
 * The thresholds were tuned against a 79 day, 156k position export in the
 * `optimizer/` Python playground, where 40 % of the positions failed the trust
 * filter, 0.8 % were spikes and 29 % belonged to a pause.
 *
 * After smoothing, every segment is cut into legs at its pauses, and each leg is
 * classified as walking, bike or travel by its speed — see [classifyMovement] and
 * [smoothMovement].
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

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** How the device got from one pause to the next, see [classifyMovement]. */
    enum class MovementMode(val minSeconds: Double) {
        Walking(WALKING_MIN_SECONDS),
        Bike(BIKE_MIN_SECONDS),
        Travel(TRAVEL_MIN_SECONDS)
    }

    /**
     * A stretch between two pauses and how the device covered it. [measuredMode]
     * is what its own speed says, [mode] what is left after [smoothMovement].
     */
    data class Leg(
        val positions: List<Position>,
        val distanceMeters: Double,
        val speedKmh: Double,
        val measuredMode: MovementMode,
        val mode: MovementMode = measuredMode
    ) {
        val start get() = positions.first().timestamp
        val end get() = positions.last().timestamp
    }

    /**
     * One stretch of the derived track between two recording gaps, together with how
     * the device moved on it — the legs after [smoothMovement].
     */
    data class Segment(val positions: List<Position>, val legs: List<Leg>)

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
     * stages and classifies the movement on every resulting segment.
     */
    fun process(positions: List<Position>): List<Segment> = positions
        .let(::dropUntrusted)
        .let(::dropGapSpikes)
        .let(::splitSegments)
        .map { segment ->
            val track = smooth(collapseStationary(dropSpikes(dropStaleRepeats(segment))))
            Segment(track, smoothMovement(classifiedLegs(track)))
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
     * back: both neighbours are much closer to each other than to it, further
     * apart than their accuracy explains.
     */
    private fun isSpike(previous: Position, current: Position, following: Position): Boolean {
        val toCurrent = distance(previous, current)
        val fromCurrent = distance(current, following)
        val skipping = distance(previous, following)

        val excursion = min(toCurrent, fromCurrent)
        val noise = previous.accuracy + current.accuracy

        val returns = skipping <= SPIKE_RETURN_RATIO * (toCurrent + fromCurrent)
        val farEnough = excursion >= max(SPIKE_MIN_EXCURSION_METERS, noise)

        return farEnough && returns
    }

    /**
     * Removes positions that jump away and immediately come back.
     *
     * Real movement continues in some direction. GPS noise around a spot
     * leaves the track and returns to where it came from, so the two
     * neighbours of a spike are close to each other while both are far away
     * from the position in between.
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

            for (index in start + 1 until kept.lastIndex) {
                val previous = survivors.last()
                val current = kept[index]
                val following = kept[index + 1]

                if (isSpike(previous, current, following)) {
                    removed++
                    continue
                }

                // Only credible as a jump if the successor stays reachable —
                // otherwise the whole stretch moved, and this position is fine.
                if (unreachable(previous, current) && !unreachable(previous, following)) {
                    removed++
                    continue
                }

                survivors += current
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
     * Moves every moving position onto a line fitted through its neighbours in
     * time, weighted by [SMOOTHING_SIGMA_SECONDS] and by accuracy.
     *
     * Positions that share their coordinates with a neighbour are pinned: that is
     * the arrival and departure pair [collapseStationary] leaves behind, or a device
     * standing still. They are neither moved nor looked past, so the movement on
     * one side of a stop does not leak into the other and the stop keeps a speed of
     * zero. The segment edges are pinned as well — with neighbours on one side only,
     * the average would drag them into the segment.
     */
    private fun smooth(segment: List<Position>): List<Position> {
        fun samePlace(first: Position, second: Position) =
            first.latitude == second.latitude && first.longitude == second.longitude

        val pinned = segment.indices.map { index ->
            index == 0 || index == segment.lastIndex ||
                    samePlace(segment[index - 1], segment[index]) ||
                    samePlace(segment[index], segment[index + 1])
        }

        return segment.mapIndexed { index, position ->
            if (pinned[index]) return@mapIndexed position

            /*
             * A weighted mean would be pulled towards whichever side has more
             * neighbours — a fix missing on one side shifts a fast device by a
             * hundred metres. Fitting a straight line through the neighbours and
             * reading it at this position's time stays on the track however they
             * are spread. Offsets from the position keep the sums small.
             */
            var weightSum = 0.0
            var weightedTime = 0.0
            var weightedTimeSquared = 0.0
            var latitude = 0.0
            var longitude = 0.0
            var latitudeTime = 0.0
            var longitudeTime = 0.0

            fun include(neighbour: Position) {
                val time = seconds(position, neighbour)
                val weight = exp(-time.pow(2) / (2 * SMOOTHING_SIGMA_SECONDS.pow(2))) /
                        max(neighbour.accuracy, 1.0).pow(2)
                val latitudeOffset = neighbour.latitude - position.latitude
                val longitudeOffset = neighbour.longitude - position.longitude

                weightSum += weight
                weightedTime += weight * time
                weightedTimeSquared += weight * time * time
                latitude += weight * latitudeOffset
                longitude += weight * longitudeOffset
                latitudeTime += weight * time * latitudeOffset
                longitudeTime += weight * time * longitudeOffset
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

            // Zero without a neighbour: no line to fit, the position stays as it is.
            val determinant = weightSum * weightedTimeSquared - weightedTime * weightedTime
            if (determinant <= 0) return@mapIndexed position

            position.copy(
                latitude = position.latitude +
                        (weightedTimeSquared * latitude - weightedTime * latitudeTime) / determinant,
                longitude = position.longitude +
                        (weightedTimeSquared * longitude - weightedTime * longitudeTime) / determinant
            )
        }
    }

    /** The legs of [segment] that cover enough ground to be judged, see [classifyMovement]. */
    private fun classifiedLegs(segment: List<Position>): List<Leg> = legs(segment).mapNotNull { positions ->
        val distance = positions.zipWithNext().sumOf { (first, second) -> distance(first, second) }
        if (distance < MOVEMENT_MIN_DISTANCE_METERS) return@mapNotNull null

        val speed = speedPercentileKmh(positions, MOVEMENT_SPEED_PERCENTILE) ?: return@mapNotNull null
        Leg(positions, distance, speed, classifyMovement(speed))
    }

    /**
     * Irons out modes that do not last: within a trip, a stretch of the same mode
     * shorter than [MovementMode.minSeconds] takes the mode of its longer neighbour.
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
                modes[run.first] == MovementMode.Bike &&
                        (slowdownsPerKm(trip.slice(run)) ?: 0.0) > BIKE_MAX_SLOWDOWNS_PER_KM
            }
            if (stopAndGo.isEmpty()) break

            for (run in stopAndGo) for (index in run) modes[index] = MovementMode.Travel
        }

        trip.mapIndexed { index, leg -> leg.copy(mode = modes[index]) }
    }

    /** Merges the stretches of [modes] that are too short, see [smoothMovement]. */
    private fun smoothRuns(trip: List<Leg>, modes: MutableList<MovementMode>) {
        fun duration(run: IntRange) = seconds(trip[run.first].positions.first(), trip[run.last].positions.last())

        while (true) {
            val runs = runRanges(modes)
            if (runs.size < 2) break

            fun minSeconds(index: Int): Double {
                val own = modes[runs[index].first].minSeconds
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
    private fun runRanges(modes: List<MovementMode>): List<IntRange> {
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
     * The stretches of [segment] between two pauses, each with at least two positions.
     *
     * A pause is either the arrival and departure pair [collapseStationary] leaves
     * behind or a single step that is long and slow, see [PAUSE_MAX_KMH]: a phone
     * that stands still often stops delivering fixes altogether, so a stop at a
     * station or a traffic light never gathers the positions a collapse needs.
     */
    private fun legs(segment: List<Position>): List<List<Position>> {
        val legs = mutableListOf<List<Position>>()
        var current = mutableListOf<Position>()

        for (position in segment) {
            val previous = current.lastOrNull()
            val pause = previous != null && (
                    (previous.latitude == position.latitude && previous.longitude == position.longitude) ||
                            (seconds(previous, position) >= STATIONARY_MIN_SECONDS &&
                                    distance(previous, position) / seconds(previous, position) * 3.6 <= PAUSE_MAX_KMH)
                    )

            if (pause) {
                if (current.size >= 2) legs += current
                current = mutableListOf()
            }

            current += position
        }

        if (current.size >= 2) legs += current
        return legs
    }

    /**
     * The speed [leg] stays below for [percentile] of its time. Weighted by time
     * rather than by step, so a burst of closely spaced fixes does not count more
     * than the same stretch recorded sparsely. Null if no time passes on the leg.
     */
    private fun speedPercentileKmh(leg: List<Position>, percentile: Double): Double? {
        val steps = leg.zipWithNext()
            .mapNotNull { (first, second) ->
                val seconds = seconds(first, second)
                if (seconds <= 0) null else distance(first, second) / seconds * 3.6 to seconds
            }
            .sortedBy { (speed, _) -> speed }

        val total = steps.sumOf { (_, seconds) -> seconds }
        if (total <= 0) return null

        var covered = 0.0
        for ((speed, seconds) in steps) {
            covered += seconds
            if (covered >= percentile * total) return speed
        }

        return steps.last().first
    }

    private fun classifyMovement(speedKmh: Double): MovementMode = when {
        speedKmh <= WALKING_MAX_KMH -> MovementMode.Walking
        speedKmh <= BIKE_MAX_KMH -> MovementMode.Bike
        else -> MovementMode.Travel
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
