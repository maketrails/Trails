package es.jvbabi.trails.data

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Derives a track worth drawing from the raw positions a device reported.
 *
 * The raw positions (`is_raw = true`) are the measurements and are never
 * touched. Everything this class writes is a second, derived series
 * (`is_raw = false`) that can be thrown away and rebuilt from the raw ones at
 * any time.
 *
 * A run only touches what is not optimized yet, plus the last
 * [REBUILD_OVERLAP] of what is: a pause that has grown since the previous run
 * has to be recomputed together with the positions it started with, and those
 * were already written.
 *
 * Four stages, in this order:
 * 1. **Trust filter** — only positions below [MAX_ACCURACY_METERS] are used.
 *    Anything worse cannot be told apart from noise.
 * 2. **Segmenting** — a recording pause longer than [SEGMENT_GAP_SECONDS]
 *    separates two independent stretches of movement.
 * 3. **Spike removal** — a position whose two neighbours are much closer to
 *    each other than to it is GPS noise: real movement continues in some
 *    direction, noise leaves and comes back.
 * 4. **Stationary collapse** — a cloud of positions that stays inside
 *    [STATIONARY_RADIUS_METERS] for at least [STATIONARY_MIN_SECONDS] becomes
 *    one accuracy weighted center, written twice so the pause keeps its
 *    duration instead of the device wandering around while it sits still.
 *
 * The thresholds were tuned against a 79 day, 156k position export in the
 * `optimizer/` Python playground, where 40 % of the positions failed the trust
 * filter, 0.8 % were spikes and 29 % belonged to a pause.
 */
class TrailOptimizer(device: Device) : KoinComponent {

    private val db by inject<DatabaseManager>()

    /**
     * Captured once at construction: an Exposed entity belongs to the
     * transaction it was read in, while this instance outlives it.
     */
    private val deviceId = device.id

    /**
     * Two runs for the same device would delete and rebuild the derived series
     * at the same time, so a device optimizes one run at a time. Overlapping
     * calls are dropped rather than queued — see [optimize]. This only holds as
     * long as the instance is shared per device.
     */
    private val runLock = Mutex()

    companion object {
        /**
         * The most recent positions are left alone: a pause may still be
         * growing, and a spike is only recognisable once its successor has
         * arrived.
         */
        val IGNORE_LATEST: Duration = 10.minutes

        /**
         * How far a run reaches back into the already optimized range. The
         * stretch right behind the previous run's end needs its context to come
         * out the same: a pause is only recognisable together with the
         * positions that started it.
         */
        val REBUILD_OVERLAP: Duration = 30.minutes

        /** How many raw positions one pass reads, optimizes and writes back. */
        const val BATCH_SIZE = 500

        const val MAX_ACCURACY_METERS = 20.0
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
        const val STATIONARY_MIN_SECONDS = 60.0
        const val STATIONARY_MIN_POINTS = 3

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    /**
     * One position on its way through the pipeline. Carries the columns that
     * are not part of the optimization so a derived position can keep the
     * bearing and battery state of the measurement it came from.
     */
    private data class Position(
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
     * Rebuilds the derived series for everything that has settled.
     *
     * A run that is already in progress covers whatever this call would find,
     * so an overlapping call returns immediately instead of queueing behind it.
     */
    suspend fun optimize() {
        if (!runLock.tryLock()) return

        try {
            rebuild()
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun rebuild() {
        // Everything younger than this may still change and is left raw.
        val upperOptimizationBound = Clock.System.now() - IGNORE_LATEST

        val (hasRawPoints, lowerOptimizationBound) = db.transaction {
            val hasRawPoints = !DataSnapshot
                .find {
                    (DataSnapshots.device eq deviceId) and
                            (DataSnapshots.isRaw eq true) and
                            (DataSnapshots.createdAt lessEq upperOptimizationBound)
                }
                .limit(1)
                .empty()

            // Where the derived series currently ends. Null means nothing has
            // been optimized yet, and the whole history is up for it.
            val optimizedUntil = DataSnapshots
                .select(Max(DataSnapshots.createdAt, columnType = KotlinInstantColumnType()).alias("max_created_at"))
                .where { DataSnapshots.device eq deviceId }
                .andWhere { DataSnapshots.isRaw eq false }
                .singleOrNull()
                ?.let { it[Max(DataSnapshots.createdAt, columnType = KotlinInstantColumnType()).alias("max_created_at")] }

            hasRawPoints to optimizedUntil?.minus(REBUILD_OVERLAP)
        }

        // Nothing has settled yet, so the derived series stays as it is - it
        // must not be deleted without being rebuilt right after.
        if (!hasRawPoints) return

        /*
         * Whatever this optimizer produced from the bound onwards is discarded
         * before that range is derived again, so a run stays idempotent and the
         * seam between two runs cannot end up with both results in it.
         */
        db.transaction {
            DataSnapshots.deleteWhere {
                val derived = (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq false)

                if (lowerOptimizationBound == null) derived
                else derived and (DataSnapshots.createdAt greaterEq lowerOptimizationBound)
            }
        }

        /*
         * Read, optimize and write one batch at a time: the whole history of a
         * device does not have to fit in memory, and a long rebuild does not
         * hold a single transaction open. A batch boundary can cut a pause in
         * two, which costs one extra position in the result.
         */
        var cursor: Instant? = null

        while (true) {
            val batch = db.transaction {
                readRawBatch(lowerOptimizationBound, upperOptimizationBound, cursor)
            }

            if (batch.isEmpty()) break

            val optimized = optimize(batch)

            if (optimized.isNotEmpty()) {
                db.transaction { write(optimized) }
            }

            cursor = batch.last().timestamp

            if (batch.size < BATCH_SIZE) break
        }
    }

    private fun readRawBatch(
        lowerOptimizationBound: Instant?,
        upperOptimizationBound: Instant,
        cursor: Instant?
    ): List<Position> = DataSnapshot
        .find {
            val window = when {
                cursor != null -> DataSnapshots.createdAt greater cursor
                lowerOptimizationBound != null -> DataSnapshots.createdAt greaterEq lowerOptimizationBound
                else -> Op.TRUE
            }

            (DataSnapshots.device eq deviceId) and
                    (DataSnapshots.isRaw eq true) and
                    (DataSnapshots.createdAt lessEq upperOptimizationBound) and
                    window
        }
        .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
        .limit(BATCH_SIZE)
        .map { snapshot ->
            Position(
                timestamp = snapshot.createdAt,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                accuracy = snapshot.locationAccuracy,
                bearing = snapshot.bearing,
                bearingAccuracy = snapshot.bearingAccuracy,
                batteryLevel = snapshot.batteryLevel,
                batteryCharging = snapshot.batteryCharging
            )
        }

    private fun write(positions: List<Position>) {
        DataSnapshots.batchInsert(positions) { position ->
            this[DataSnapshots.device] = deviceId
            this[DataSnapshots.createdAt] = position.timestamp
            this[DataSnapshots.latitude] = position.latitude
            this[DataSnapshots.longitude] = position.longitude
            this[DataSnapshots.locationAccuracy] = position.accuracy
            this[DataSnapshots.bearing] = position.bearing
            this[DataSnapshots.bearingAccuracy] = position.bearingAccuracy
            this[DataSnapshots.batteryLevel] = position.batteryLevel
            this[DataSnapshots.batteryCharging] = position.batteryCharging
            this[DataSnapshots.isRaw] = false
        }
    }

    private fun optimize(positions: List<Position>): List<Position> = positions
        .filter { it.accuracy < MAX_ACCURACY_METERS }
        .let(::splitSegments)
        .flatMap { segment -> collapseStationary(dropSpikes(segment)) }

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

            val survivors = mutableListOf(kept.first())
            var removed = 0

            for (index in 1 until kept.lastIndex) {
                val previous = survivors.last()
                val current = kept[index]
                val following = kept[index + 1]

                val toCurrent = distance(previous, current)
                val fromCurrent = distance(current, following)
                val skipping = distance(previous, following)

                val excursion = min(toCurrent, fromCurrent)
                val noise = previous.accuracy + current.accuracy

                val returns = skipping <= SPIKE_RETURN_RATIO * (toCurrent + fromCurrent)
                val farEnough = excursion >= max(SPIKE_MIN_EXCURSION_METERS, noise)

                if (farEnough && returns) {
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

            survivors += kept.last()
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

    private fun distance(
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
}
