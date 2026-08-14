package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.Share
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.trails.api.v1.entity.Share as ShareEntity

/**
 * A share its owner emitted: the permission to watch one device, on the terms
 * recorded here. [ownerId] is carried along because every caller checks it, and a
 * model must answer that without going back to the database.
 */
data class ShareModel(
    val id: Uuid,
    val deviceId: Uuid,
    val ownerId: Uuid,
    val shareName: String,
    /**
     * How far back the share reveals the device's history: `0` nothing at all,
     * negative an unbounded window, otherwise that many seconds.
     */
    val locationHistorySeconds: Int,
    val shareBatteryState: Boolean,
    val isLocked: Boolean,
    val allowMultiuse: Boolean,
    val createdAt: Instant,
) {
    /** Whether the share reveals any history beyond the current position. */
    val revealsHistory: Boolean get() = locationHistorySeconds != 0

    /** The window as a bound on recording time, or `null` when unbounded. */
    val historySeconds: Int? get() = locationHistorySeconds.takeIf { it > 0 }
}

/** Must be called inside a transaction. */
fun Share.toModel() = ShareModel(
    id = id.value,
    deviceId = device.id.value,
    ownerId = device.owner.id.value,
    shareName = shareName,
    locationHistorySeconds = locationHistorySeconds,
    shareBatteryState = shareBatteryState,
    isLocked = isLocked,
    allowMultiuse = allowMultiuse,
    createdAt = createdAt,
)

/** The share as the API hands it out to its owner. */
fun ShareModel.toApi() = ShareEntity(
    id = id,
    deviceId = deviceId,
    shareName = shareName,
    locationHistorySeconds = locationHistorySeconds,
    shareBatteryState = shareBatteryState,
    allowMultiuse = allowMultiuse,
    isLocked = isLocked,
)

/**
 * The position as this share may reveal it: the charge level is withheld unless the
 * share opted in.
 *
 * The rule lives with the share rather than with each endpoint, so "what a share
 * gives away" is decided in one place.
 */
fun SnapshotModel.forShare(share: ShareModel): SnapshotModel =
    if (share.shareBatteryState) this
    else copy(batteryLevel = null, batteryCharging = null)
