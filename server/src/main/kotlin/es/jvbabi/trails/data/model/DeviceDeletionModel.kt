package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.DeviceDeletion
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Why and by whom a device was removed. [deletedByDeviceName] is the device that
 * triggered it, or `null` when it came from a browser session — which has no device
 * to name.
 */
data class DeviceDeletionModel(
    val id: Uuid,
    val deviceId: Uuid,
    val deletedByDeviceName: String?,
    val deletedAt: Instant,
)

/** Must be called inside a transaction. */
fun DeviceDeletion.toModel() = DeviceDeletionModel(
    id = id.value,
    deviceId = device.id.value,
    // Resolved here rather than kept as a session id: every consumer wants the name,
    // and only a transaction can still reach it.
    deletedByDeviceName = deletedBy?.device?.displayName,
    deletedAt = deletedAt,
)
