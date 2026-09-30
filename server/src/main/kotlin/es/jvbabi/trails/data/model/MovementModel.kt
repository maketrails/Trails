package es.jvbabi.trails.data.model

import es.jvbabi.trails.api.v1.history.MovementItem
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A stored [Movement], detached from the database. */
data class MovementModel(
    val id: Uuid,
    val startsAt: Instant,
    val endsAt: Instant,
    val distanceMeters: Double,
    val type: Movement.Type,
    val insertedAt: Instant,
)

fun Movement.toModel() = MovementModel(
    id = id.value,
    startsAt = startsAt,
    endsAt = endsAt,
    distanceMeters = distanceMeters,
    type = type,
    insertedAt = insertedAt,
)

fun MovementModel.toApi() = MovementItem(
    id = id,
    from = startsAt.toEpochMilliseconds(),
    to = endsAt.toEpochMilliseconds(),
    distanceMeters = distanceMeters.roundToInt(),
    type = when (type) {
        Movement.Type.Walking -> "walking"
        Movement.Type.Bike -> "cycling"
        is Movement.Type.Travel -> "travel"
    },
)
