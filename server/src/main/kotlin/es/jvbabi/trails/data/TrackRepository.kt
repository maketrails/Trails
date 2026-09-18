package es.jvbabi.trails.data

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.data.event.DeviceEvent
import es.jvbabi.trails.data.model.SnapshotModel
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Which of a device's two series a caller wants to see.
 *
 * The optimized track is what a map should normally draw; the raw measurements are
 * what the device actually reported, jitter and standstill clouds included.
 */
enum class TrackSource {
    /** The optimized series as far as it reaches, then the raw tail behind it. */
    Optimized,

    /** Only the measurements, exactly as they were recorded. */
    Raw,
}

/**
 * How storing a reported position went.
 *
 * A device that gets no acknowledgement uploads again, so "we already have it" has
 * to be told apart from "it did not land": the first is a success from the device's
 * side, the second must not be acknowledged.
 */
sealed interface SnapshotWriteResult {
    data class Stored(val snapshot: SnapshotModel) : SnapshotWriteResult
    data object AlreadyStored : SnapshotWriteResult
    data class Failed(val error: Throwable) : SnapshotWriteResult
}

/**
 * A device's recorded positions: the measurements it reported and the optimized
 * series derived from them.
 *
 * Kept apart from [DeviceRepository] because it is a different kind of thing — a
 * device is one row that changes, its track is an ever growing series that is only
 * ever appended to and rebuilt in bulk. A stored position still announces itself as
 * a [DeviceEvent.SnapshotAdded] on the device's stream, so a subscriber sees one
 * device with one history rather than two sources.
 */
class TrackRepository : KoinComponent {
    companion object {
        /** How many positions one chunk of a `?chunked=true` history read holds. */
        const val CHUNK_SIZE = 10_000
    }

    private val db by inject<DatabaseManager>()
    private val deviceRepository by inject<DeviceRepository>()

    /**
     * Stores a position a device reported and announces it.
     *
     * Idempotent in two ways, because a device retries whatever was not
     * acknowledged: the same [snapshotId] twice, and a second measurement for a
     * second the device already has — the unique `(device, timestamp, is_raw)` index
     * allows only one. Two uploads can race past the check, so a failed write is
     * re-examined instead of being reported as a failure.
     */
    suspend fun addSnapshot(
        deviceId: Uuid,
        snapshotId: Uuid,
        recordedAt: Instant,
        latitude: Double,
        longitude: Double,
        locationAccuracy: Double,
        bearing: Double,
        bearingAccuracy: Double?,
        batteryLevel: Float?,
        batteryCharging: Boolean?,
    ): SnapshotWriteResult {
        // Must run inside a transaction.
        val isAlreadyStored = {
            DataSnapshot.findById(snapshotId) != null ||
                    !DataSnapshot.find {
                        (DataSnapshots.device eq deviceId) and
                                (DataSnapshots.createdAt eq recordedAt) and
                                (DataSnapshots.isRaw eq true)
                    }.empty()
        }

        val stored = runCatching {
            db.transaction {
                if (isAlreadyStored()) return@transaction null
                val device = Device.findById(deviceId) ?: return@transaction null

                DataSnapshot.new(snapshotId) {
                    this.device = device
                    this.latitude = latitude
                    this.longitude = longitude
                    this.bearing = bearing
                    this.bearingAccuracy = bearingAccuracy
                    this.locationAccuracy = locationAccuracy
                    this.batteryLevel = batteryLevel
                    this.batteryCharging = batteryCharging
                    this.createdAt = recordedAt
                    this.isRaw = true
                }.toModel()
            }
        }.getOrElse { error ->
            val landed = runCatching { db.transaction(isAlreadyStored) }.getOrDefault(false)
            return if (landed) SnapshotWriteResult.AlreadyStored else SnapshotWriteResult.Failed(error)
        } ?: return SnapshotWriteResult.AlreadyStored

        deviceRepository.publish(DeviceEvent.SnapshotAdded(stored))
        return SnapshotWriteResult.Stored(stored)
    }

    /**
     * Where the server last saw the device, or `null` when it never reported — or
     * reported nothing since [notOlderThan], which is how a share's retention window
     * applies.
     *
     * Both series are searched: an optimized row rewrites a raw one and keeps its
     * recording time, so the newest row is the newest position either way.
     */
    suspend fun latestSnapshot(deviceId: Uuid, notOlderThan: Instant? = null): SnapshotModel? =
        db.transaction {
            val recorded = notOlderThan?.let { DataSnapshots.createdAt greaterEq it } ?: Op.TRUE

            DataSnapshot
                .find { (DataSnapshots.device eq deviceId) and recorded }
                .orderBy(DataSnapshots.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toModel()
        }

    /**
     * The recorded positions of a device the way a track should show them, oldest
     * first.
     *
     * For [TrackSource.Optimized] that is the optimized series as far as it reaches,
     * then the raw measurements that come after it. The optimizer only covers what
     * has settled, so the newest positions are always still raw: handing out the
     * optimized series alone would make a track end minutes in the past, and mixing
     * both everywhere would draw every stretch twice. Switching over at the last
     * optimized position gives one continuous track — clean where it can be, live at
     * the tip.
     *
     * The two windows cut along different time axes and both apply: [notOlderThan]
     * is about *when a position was recorded*, [storedSince] about *when the row was
     * written*. Conflating them would either let a share reveal positions past its
     * retention window, or make an incremental read miss the optimized positions a
     * rebuild has just written under old timestamps.
     */
    suspend fun track(
        deviceId: Uuid,
        notOlderThan: Instant? = null,
        storedSince: Instant? = null,
        source: TrackSource = TrackSource.Optimized,
    ): List<SnapshotModel> = db.transaction {
        series(deviceId, notOlderThan, storedSince, source).flatMap { condition ->
            DataSnapshot
                .find { condition }
                .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
                .map { it.toModel() }
        }
    }

    /**
     * One chunk of [track]: at most [limit] positions recorded after [recordedAfter],
     * oldest first, and how many of the track come after them.
     *
     * The recording time is unique within a track, so the last position of one chunk
     * is where the next one continues — unlike an offset, that stays correct while new
     * positions arrive at the tip.
     */
    suspend fun trackChunk(
        deviceId: Uuid,
        notOlderThan: Instant? = null,
        storedSince: Instant? = null,
        recordedAfter: Instant? = null,
        limit: Int,
        source: TrackSource = TrackSource.Optimized,
    ): TrackChunk = db.transaction {
        val series = series(deviceId, notOlderThan, storedSince, source)
        val after = recordedAfter?.let { DataSnapshots.createdAt greater it } ?: Op.TRUE

        val points = mutableListOf<SnapshotModel>()
        for (condition in series) {
            if (points.size >= limit) break
            points += DataSnapshot
                .find { condition and after }
                .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
                .limit(limit - points.size)
                .map { it.toModel() }
        }

        // A chunk that did not fill up is the last one, so only a full one is counted.
        val remaining = if (points.size < limit) 0L else {
            val rest = DataSnapshots.createdAt greater points.last().createdAt
            series.sumOf { condition -> DataSnapshots.selectAll().where(condition and rest).count() }
        }

        TrackChunk(points = points, remaining = remaining)
    }

    /**
     * The conditions selecting a track, one per series, in the order they follow each
     * other in time — see [track] for how the optimized track is put together.
     *
     * Must run inside a transaction.
     */
    private fun series(
        deviceId: Uuid,
        notOlderThan: Instant?,
        storedSince: Instant?,
        source: TrackSource,
    ): List<Op<Boolean>> {
        val recorded = notOlderThan?.let { DataSnapshots.createdAt greaterEq it } ?: Op.TRUE
        val stored = storedSince?.let { DataSnapshots.insertedAt greaterEq it } ?: Op.TRUE
        val window = recorded and stored

        val raw = (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq true)
        if (source == TrackSource.Raw) return listOf(raw and window)

        // Deliberately unwindowed: where the optimized series ends is a property of
        // the whole track, so the raw tail starts at the same position no matter how
        // little of the track this call is about to return.
        val optimizedEnd = DataSnapshot
            .find { (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq false) }
            .orderBy(DataSnapshots.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.createdAt

        val tail = optimizedEnd?.let { DataSnapshots.createdAt greater it } ?: Op.TRUE

        return listOf(
            (DataSnapshots.device eq deviceId) and (DataSnapshots.isRaw eq false) and window,
            raw and tail and window,
        )
    }
}

/** A part of a track and how many positions of it come after the part. */
data class TrackChunk(
    val points: List<SnapshotModel>,
    val remaining: Long,
)

