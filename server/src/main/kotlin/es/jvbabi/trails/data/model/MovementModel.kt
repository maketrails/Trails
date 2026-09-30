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

/**
 * This movement as a reader who may only see what happened from [windowStart] on sees
 * it — a share's retention window. Null if it ended before; unchanged if it started
 * inside the window, or there is none.
 *
 * One that reaches into the window is cut at its start, and its distance is measured
 * anew by [distanceBetween] over what lies inside: the stored distance covers the part
 * before the window too, and would give away how far the device went there.
 */
fun MovementModel.visibleFrom(
    windowStart: Instant?,
    distanceBetween: (from: Instant, to: Instant) -> Double,
): MovementModel? = when {
    windowStart == null || startsAt >= windowStart -> this
    endsAt < windowStart -> null
    else -> copy(startsAt = windowStart, distanceMeters = distanceBetween(windowStart, endsAt))
}

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
