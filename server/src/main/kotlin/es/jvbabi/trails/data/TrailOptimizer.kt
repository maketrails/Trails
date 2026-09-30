package es.jvbabi.trails.data

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.api.v1.optimization.DeviceOptimizationResponse.OptimizationProgress
import es.jvbabi.trails.data.model.Movement
import es.jvbabi.trails.data.model.Movements
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.TrackRebuild
import es.jvbabi.trails.data.TrackPipeline.Position
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
 * were already written. A position uploaded late — a device catching up after
 * being offline — reaches further back: the run then starts [REBUILD_OVERLAP]
 * before the oldest position stored since the previous run.
 *
 * The algorithm itself lives in [TrackPipeline]; this class feeds it batch by
 * batch and writes the result back.
 */
class TrailOptimizer(
    private val deviceId: Uuid,
    private val ownerId: Uuid,
) : KoinComponent {

    private val db by inject<DatabaseManager>()

    private val deviceRepository by inject<DeviceRepository>()

    /** The measurements of this device — the input, never written to. */
    private val raw get() = (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq true)

    /** The series this optimizer produced — the output, freely disposable. */
    private val derived get() = (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq false)

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

        /**
         * Breather between two batches, so concurrent writers are not starved by
         * a long rebuild.
         */
        val BATCH_PAUSE: Duration = 50.milliseconds

        private val MAX_CREATED_AT = Max(
            DataSnapshots.createdAt,
            columnType = KotlinInstantColumnType()
        ).alias("max_created_at")

        private val MIN_CREATED_AT = Min(
            DataSnapshots.createdAt,
            columnType = KotlinInstantColumnType()
        ).alias("min_created_at")

        private val MIN_STARTS_AT = Min(
            Movements.startsAt,
            columnType = KotlinInstantColumnType()
        ).alias("min_starts_at")

        private val MAX_INSERTED_AT = Max(
            DataSnapshots.insertedAt,
            columnType = KotlinInstantColumnType()
        ).alias("max_inserted_at")
    }

    /**
     * When the previous successful run started. Raw positions stored since then are
     * new to the optimizer, however long ago they were recorded. Only touched under
     * [runLock]; null after a restart, see [storedSinceLastRun].
     */
    private var lastRunStartedAt: Instant? = null

    /**
     * What the device details view shows about the optimization.
     *
     * The point counts are disjoint: [optimizedPoints] counts the derived
     * series, [unoptimizedPoints] the raw positions behind the point the
     * optimizer has reached. The two are not expected to match — the pipeline
     * drops roughly two thirds of what it reads.
     *
     * Both distances skip steps across a recording pause longer than
     * [TrackPipeline.SEGMENT_GAP_SECONDS]; without that, a gap of days between two positions
     * would count as a straight line of hundreds of kilometres.
     */
    data class OptimizationState(
        val optimizedPoints: Long,
        val unoptimizedPoints: Long,
        val rawPoints: Long,
        val optimizedDistanceMeters: Double,
        val unoptimizedDistanceMeters: Double,
        val rawDistanceMeters: Double,
        /** When [reoptimize] last rebuilt the track from scratch, null if never. */
        val rebuiltAt: Instant?,
        val progress: OptimizationProgress
    )

    /** How many positions a series holds and how far it runs. */
    private data class Measurement(val points: Long, val distanceMeters: Double)

    /**
     * What one run set out to do, measured once at its start.
     *
     * Progress is counted forward from here rather than re-queried per batch:
     * two COUNTs after every batch is a lot of database traffic for a number
     * that only feeds a progress bar.
     */
    private data class Window(
        val lowerBound: Instant?,
        val settledPoints: Long,
        val previouslyProcessed: Long
    ) {
        fun progressAt(processed: Long): Double = if (settledPoints == 0L) 1.0
        else ((previouslyProcessed + processed).toDouble() / settledPoints).coerceIn(0.0, 1.0)
    }


    private val logger = KtorSimpleLogger("TrailOptimizer")


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

    /**
     * Throws the whole derived series away and derives it again.
     *
     * Unlike [optimize] this waits for a run in progress instead of dropping
     * the call: it is a deliberate request, not a tick that the running pass
     * already covers.
     */
    suspend fun reoptimize() = runLock.withLock {
        db.transaction {
            DataSnapshots.deleteWhere { derived }
            Movements.deleteWhere { Movements.device eq deviceId }

            // Recorded with the delete, so no client can read the emptied track
            // without also being able to see that it was reset.
            val now = Clock.System.now()
            TrackRebuild.findById(deviceId)?.apply { rebuiltAt = now }
                ?: TrackRebuild.new(deviceId) { rebuiltAt = now }
        }

        rebuild()
    }

    /** The numbers the device details view shows about this device. */
    suspend fun state(): OptimizationState = db.transaction {
        val optimizedUntil = derivedEnd()

        val unoptimized = if (optimizedUntil == null) raw
        else raw and (DataSnapshots.createdAt greater optimizedUntil)

        val optimizedSeries = measure(derived)
        val unoptimizedSeries = measure(unoptimized)
        val rawSeries = measure(raw)

        OptimizationState(
            optimizedPoints = optimizedSeries.points,
            unoptimizedPoints = unoptimizedSeries.points,
            rawPoints = rawSeries.points,
            optimizedDistanceMeters = optimizedSeries.distanceMeters,
            unoptimizedDistanceMeters = unoptimizedSeries.distanceMeters,
            rawDistanceMeters = rawSeries.distanceMeters,
            rebuiltAt = TrackRebuild.findById(deviceId)?.rebuiltAt,
            progress = if (runLock.isLocked) OptimizationProgress.Running(progress(optimizedUntil))
            else OptimizationProgress.Idle
        )
    }

    private suspend fun rebuild() {
        val startedAt = Clock.System.now()

        // Everything younger than this may still change and is left raw.
        val upperOptimizationBound = startedAt - IGNORE_LATEST

        val window = db.transaction {
            val settled = DataSnapshots
                .selectAll()
                .where(raw and (DataSnapshots.createdAt lessEq upperOptimizationBound))
                .count()

            // Null means nothing has been optimized yet, and the whole history
            // is up for it.
            val lowerBound = derivedEnd()
                ?.minus(REBUILD_OVERLAP)
                ?.let { overlap ->
                    val oldestLate = oldestStoredSince(storedSinceLastRun(), recordedBefore = overlap)
                    oldestLate?.minus(REBUILD_OVERLAP)?.coerceAtMost(overlap) ?: overlap
                }
                ?.let { bound -> movementAcross(bound)?.coerceAtMost(bound) ?: bound }

            Window(
                lowerBound = lowerBound,
                settledPoints = settled,
                // What the run inherits from earlier ones, so its progress can be
                // counted forward from here without asking the database again.
                previouslyProcessed = lowerBound
                    ?.let { DataSnapshots.selectAll().where(raw and (DataSnapshots.createdAt less it)).count() }
                    ?: 0L
            )
        }

        // Nothing has settled yet, so the derived series stays as it is - it
        // must not be deleted without being rebuilt right after.
        if (window.settledPoints == 0L) {
            lastRunStartedAt = startedAt
            return
        }

        /*
         * Whatever this optimizer produced from the bound onwards is discarded
         * before that range is derived again, so a run stays idempotent and the
         * seam between two runs cannot end up with both results in it.
         */
        db.transaction {
            DataSnapshots.deleteWhere {
                val lowerBound = window.lowerBound

                if (lowerBound == null) derived
                else derived and (DataSnapshots.createdAt greaterEq lowerBound)
            }

            Movements.deleteWhere {
                val lowerBound = window.lowerBound

                if (lowerBound == null) Movements.device eq deviceId
                else (Movements.device eq deviceId) and (Movements.startsAt greaterEq lowerBound)
            }
        }

        publishProgress(OptimizationProgress.Running(window.progressAt(0)))

        /*
         * Read, optimize and write one batch at a time: the whole history of a
         * device does not have to fit in memory, and a long rebuild does not hold
         * a single transaction open. A batch boundary can cut a pause in two,
         * which costs one extra position in the result.
         */
        var cursor: Instant? = null
        var processed = 0L

        while (true) {
            val batch = db.transaction {
                readRawBatch(window.lowerBound, upperOptimizationBound, cursor)
            }

            if (batch.isEmpty()) break

            val segments = TrackPipeline.process(batch)
            segments.forEach(::logMovement)

            val optimized = segments.flatMap { it.positions }

            if (optimized.isNotEmpty()) {
                db.transaction {
                    write(optimized)
                    writeMovements(segments)
                }
            }

            cursor = batch.last().timestamp
            processed += batch.size

            publishProgress(OptimizationProgress.Running(window.progressAt(processed)))

            if (batch.size < BATCH_SIZE) break

            /*
             * Let other writers in. A rebuild is a background job with no
             * deadline, while an app catching up pushes snapshots in batches of
             * 50 and would otherwise queue behind every batch we write - on
             * SQLite, where there is one writer at a time, that shows up as
             * SQLITE_BUSY.
             */
            delay(BATCH_PAUSE)
        }

        // Only a run that got through moves the mark: a failed one leaves the late
        // positions for the next run to find again.
        lastRunStartedAt = startedAt

        publishProgress(OptimizationProgress.Idle)
    }

    /**
     * Since when raw positions count as new to the optimizer. After a restart the
     * in-memory mark is gone, and the last write of the derived series is the closest
     * substitute — it can miss what arrived while that run was going, nothing more.
     */
    private fun storedSinceLastRun(): Instant? = lastRunStartedAt ?: DataSnapshots
        .select(MAX_INSERTED_AT)
        .where(derived)
        .singleOrNull()
        ?.get(MAX_INSERTED_AT)

    /**
     * The oldest recording time among the raw positions stored since [storedSince]
     * that were recorded before [recordedBefore] — the late ones a run from the
     * regular bound on would never read.
     */
    private fun oldestStoredSince(storedSince: Instant?, recordedBefore: Instant): Instant? {
        if (storedSince == null) return null

        return DataSnapshots
            .select(MIN_CREATED_AT)
            .where(
                raw and
                        (DataSnapshots.insertedAt greaterEq storedSince) and
                        (DataSnapshots.createdAt less recordedBefore)
            )
            .singleOrNull()
            ?.get(MIN_CREATED_AT)
    }

    /**
     * Tells the owner's sessions how far along the device is. Runs after every
     * batch, so it costs no query at all — see [Window]. The distances of
     * [state] are a full scan and are only read when a view asks for them.
     */
    private suspend fun publishProgress(progress: OptimizationProgress) {
        deviceRepository.reportOptimizationProgress(
            deviceId = deviceId,
            ownerId = ownerId,
            progress = progress,
        )
    }

    /**
     * Share of the settled raw positions that the derived series covers.
     *
     * Only settled positions count: the newest [IGNORE_LATEST] are skipped on
     * purpose, so counting them would make the progress stick below 100 % for
     * a device that is fully optimized.
     */
    private fun progress(optimizedUntil: Instant?): Double {
        val settled = DataSnapshots
            .selectAll()
            .where(raw and (DataSnapshots.createdAt lessEq Clock.System.now() - IGNORE_LATEST))
            .count()

        if (settled == 0L) return 1.0

        val covered = optimizedUntil
            ?.let { DataSnapshots.selectAll().where(raw and (DataSnapshots.createdAt lessEq it)).count() }
            ?: 0L

        return (covered.toDouble() / settled).coerceIn(0.0, 1.0)
    }

    /** Where the derived series currently ends, or null if it is empty. */
    private fun derivedEnd(): Instant? = DataSnapshots
        .select(MAX_CREATED_AT)
        .where(derived)
        .singleOrNull()
        ?.get(MAX_CREATED_AT)

    /**
     * Counts the selected positions and adds up the distance along them in one
     * pass, oldest first.
     *
     * Steps across a recording pause longer than [TrackPipeline.SEGMENT_GAP_SECONDS] are
     * skipped: the device did travel in between, but not in a straight line we
     * know anything about.
     */
    private fun measure(condition: Op<Boolean>): Measurement {
        var points = 0L
        var total = 0.0

        var previousTimestamp: Instant? = null
        var previousLatitude = 0.0
        var previousLongitude = 0.0

        DataSnapshots
            .select(DataSnapshots.createdAt, DataSnapshots.latitude, DataSnapshots.longitude)
            .where(condition)
            .orderBy(DataSnapshots.createdAt, SortOrder.ASC)
            .forEach { row ->
                val timestamp = row[DataSnapshots.createdAt]
                val latitude = row[DataSnapshots.latitude]
                val longitude = row[DataSnapshots.longitude]

                points++

                val gap = previousTimestamp?.let { (timestamp - it).inWholeMilliseconds / 1000.0 }

                if (gap != null && gap <= TrackPipeline.SEGMENT_GAP_SECONDS) {
                    total += TrackPipeline.distance(previousLatitude, previousLongitude, latitude, longitude)
                }

                previousTimestamp = timestamp
                previousLatitude = latitude
                previousLongitude = longitude
            }

        return Measurement(points = points, distanceMeters = total)
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
        /*
         * One instant for the whole batch: a derived position carries the timestamp of
         * the measurement it came from, so `inserted_at` is the only thing that tells a
         * client this generation of the track is new. Sharing it across the batch keeps
         * a batch indivisible for a cursor — nobody can read half of one.
         */
        val insertedAt = Clock.System.now()

        DataSnapshots.batchInsert(positions) { position ->
            this[DataSnapshots.device] = deviceId
            this[DataSnapshots.createdAt] = position.timestamp
            this[DataSnapshots.insertedAt] = insertedAt
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

    /**
     * Where the movement that is still going on at [bound] started, or null if none
     * is. A run starting at [bound] would otherwise cut it in two and classify only
     * its second half — so the run reaches back to the start of it instead, and the
     * movement is derived again as a whole.
     */
    private fun movementAcross(bound: Instant): Instant? = Movements
        .select(MIN_STARTS_AT)
        .where(
            (Movements.device eq deviceId) and
                    (Movements.startsAt less bound) and
                    (Movements.endsAt greaterEq bound)
        )
        .singleOrNull()
        ?.get(MIN_STARTS_AT)

    /**
     * Stores how the device moved on [segments], one row per stretch of the same
     * type, see [TrackPipeline.runs].
     *
     * A batch boundary cuts a trip in two, and so does the start of a run. A stretch
     * that continues the latest stored movement — same type, not more than
     * [TrackPipeline.TRIP_MAX_PAUSE_SECONDS] later — therefore extends it instead of
     * starting a new one.
     */
    private fun writeMovements(segments: List<TrackPipeline.Segment>) {
        val device = Device[deviceId]

        var latest = Movement
            .find { Movements.device eq deviceId }
            .orderBy(Movements.endsAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        for (run in segments.flatMap { TrackPipeline.runs(it.legs) }) {
            val type = run.first().mode
            val start = run.first().start
            val end = run.last().end
            val distance = run.sumOf { it.distanceMeters }

            val continues = latest != null &&
                    latest.type == type &&
                    start >= latest.endsAt &&
                    (start - latest.endsAt).inWholeMilliseconds / 1000.0 <= TrackPipeline.TRIP_MAX_PAUSE_SECONDS

            latest = if (continues) {
                latest.apply {
                    endsAt = end
                    distanceMeters += distance
                }
            } else {
                Movement.new {
                    this.device = device
                    startsAt = start
                    endsAt = end
                    distanceMeters = distance
                    this.type = type
                }
            }
        }
    }

    /**
     * Logs how the device moved on [segment], one line per stretch of the same mode,
     * see [TrackPipeline.Segment.legs]. A batch boundary cuts a trip as well, so a
     * long ride can be logged in parts.
     */
    private fun logMovement(segment: TrackPipeline.Segment) {
        for (run in TrackPipeline.runs(segment.legs)) {
            val reassigned = run.count { it.mode != it.measuredMode }

            logger.info(
                "Device $deviceId: ${run.first().mode} from ${run.first().start} to ${run.last().end}, " +
                        "${"%.2f".format(run.sumOf { it.distanceMeters } / 1000)} km in ${run.size} legs" +
                        if (reassigned > 0) ", $reassigned reassigned" else ""
            )
        }
    }
}
