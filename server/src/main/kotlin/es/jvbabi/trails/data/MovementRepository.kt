package es.jvbabi.trails.data

import database.DataSnapshots
import es.jvbabi.trails.data.model.Movement
import es.jvbabi.trails.data.model.MovementModel
import es.jvbabi.trails.data.model.Movements
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.data.model.visibleFrom
import es.jvbabi.trails.database.DatabaseManager
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How a device moved, as the [TrailOptimizer] classified it. Written by the optimizer
 * alone, together with the optimized track; this is the reading side.
 */
class MovementRepository : KoinComponent {
    private val db by inject<DatabaseManager>()

    /**
     * The movements of [deviceId] written at or after [storedSince], oldest first —
     * all of them without a bound. The bound is a storage time, the same cursor an
     * incremental history read continues from.
     *
     * [notOlderThan] is a share's retention window: what ended before it is left out,
     * and a movement reaching into it is cut at its start, see [visibleFrom].
     */
    suspend fun storedSince(
        deviceId: Uuid,
        storedSince: Instant?,
        notOlderThan: Instant? = null,
    ): List<MovementModel> = db.transaction {
        Movement
            .find {
                (Movements.device eq deviceId) and
                        (storedSince?.let { Movements.insertedAt greaterEq it } ?: Op.TRUE) and
                        (notOlderThan?.let { Movements.endsAt greaterEq it } ?: Op.TRUE)
            }
            .orderBy(Movements.startsAt to SortOrder.ASC)
            .mapNotNull { movement ->
                movement.toModel().visibleFrom(notOlderThan) { from, to -> trackDistance(deviceId, from, to) }
            }
    }

    /**
     * How far the optimized track of [deviceId] runs from [from] to [to], skipping
     * recording gaps like the optimizer's own measurements do.
     */
    private fun trackDistance(deviceId: Uuid, from: Instant, to: Instant): Double {
        var total = 0.0
        var previous: Triple<Instant, Double, Double>? = null

        DataSnapshots
            .select(DataSnapshots.createdAt, DataSnapshots.latitude, DataSnapshots.longitude)
            .where(
                (DataSnapshots.device eq deviceId) and
                        (DataSnapshots.isRaw eq false) and
                        (DataSnapshots.createdAt greaterEq from) and
                        (DataSnapshots.createdAt lessEq to)
            )
            .orderBy(DataSnapshots.createdAt, SortOrder.ASC)
            .forEach { row ->
                val current = Triple(row[DataSnapshots.createdAt], row[DataSnapshots.latitude], row[DataSnapshots.longitude])
                previous?.let { (time, latitude, longitude) ->
                    val gap = (current.first - time).inWholeMilliseconds / 1000.0
                    if (gap <= TrackPipeline.SEGMENT_GAP_SECONDS) {
                        total += TrackPipeline.distance(latitude, longitude, current.second, current.third)
                    }
                }
                previous = current
            }

        return total
    }
}
