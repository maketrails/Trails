package es.jvbabi.trails.data

import es.jvbabi.trails.data.model.Movement
import es.jvbabi.trails.data.model.MovementModel
import es.jvbabi.trails.data.model.Movements
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.DatabaseManager
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
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
     */
    suspend fun storedSince(deviceId: Uuid, storedSince: Instant?): List<MovementModel> = db.transaction {
        Movement
            .find {
                (Movements.device eq deviceId) and
                        (storedSince?.let { Movements.insertedAt greaterEq it } ?: Op.TRUE)
            }
            .orderBy(Movements.startsAt to SortOrder.ASC)
            .map { it.toModel() }
    }
}
