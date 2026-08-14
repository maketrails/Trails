package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.Session
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One device's sign-in. A session is invalidated rather than deleted. */
data class SessionModel(
    val id: Uuid,
    val deviceId: Uuid,
    val createdAt: Instant,
    val invalidatedAt: Instant?,
) {
    val isValid: Boolean get() = invalidatedAt == null
}

/** Must be called inside a transaction. */
fun Session.toModel() = SessionModel(
    id = id.value,
    deviceId = device.id.value,
    createdAt = createdAt,
    invalidatedAt = invalidatedAt,
)
