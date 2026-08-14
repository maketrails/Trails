package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.DeviceType
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.trails.api.v1.entity.Device as DeviceEntity

/**
 * One of a user's devices.
 *
 * A domain model: an immutable snapshot, read inside a repository's transaction and
 * complete by the time a caller sees one. That is what keeps the database behind the
 * repositories — nothing outside [es.jvbabi.trails.data] can reach a relation
 * lazily, so there is no way to read or write state without going through a
 * repository function, and therefore no way to change it without the matching event
 * being published.
 */
data class DeviceModel(
    val id: Uuid,
    val ownerId: Uuid,
    val manufacturer: String,
    val model: String,
    /** Model-derived name, e.g. "iPhone 15 Pro". */
    val friendlyName: String,
    /** What the user sees: their own name for the device, or [defaultDisplayName]. */
    val displayName: String,
    val type: DeviceType,
    val createdAt: Instant,
    /** Set once the device was removed; devices are only ever soft-deleted. */
    val deletion: DeviceDeletionModel?,
) {
    val isDeleted: Boolean get() = deletion != null

    /** The name a device carries until its owner gives it one of their own. */
    val defaultDisplayName: String get() = "$manufacturer $friendlyName"
}

/** Reads the stored device. Must be called inside a transaction. */
fun Device.toModel() = DeviceModel(
    id = id.value,
    ownerId = owner.id.value,
    manufacturer = manufacturer,
    model = model,
    friendlyName = friendlyName,
    displayName = displayName,
    type = type,
    createdAt = createdAt,
    deletion = deletion?.toModel(),
)

/** The device as the API hands it out. */
fun DeviceModel.toApi() = DeviceEntity(
    id = id,
    manufacturer = manufacturer,
    model = model,
    friendlyName = friendlyName,
    displayName = displayName,
    ownerId = ownerId,
)
