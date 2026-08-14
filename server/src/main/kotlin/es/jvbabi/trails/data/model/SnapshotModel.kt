package es.jvbabi.trails.data.model

import database.DataSnapshot
import es.jvbabi.trails.api.v1.history.LocationHistoryPoint
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One recorded position of a device, raw as reported or derived by the optimizer.
 *
 * [createdAt] is when the position was *recorded*, [insertedAt] when this row was
 * *stored* — they come apart for uploads from an offline device and for the
 * optimizer, which rewrites old timestamps at any time. Only [insertedAt] grows with
 * the writes, which is what makes "everything since I last looked" answerable.
 */
data class SnapshotModel(
    val id: Uuid,
    val deviceId: Uuid,
    val createdAt: Instant,
    val insertedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val locationAccuracy: Double,
    val bearing: Double,
    val bearingAccuracy: Double?,
    val batteryLevel: Float?,
    val batteryCharging: Boolean?,
    val isRaw: Boolean,
) {
    /** The charge level, present only when the device reported both parts of it. */
    val battery: BatteryModel?
        get() {
            val level = batteryLevel ?: return null
            val charging = batteryCharging ?: return null
            return BatteryModel(percentage = (level * 100).roundToInt(), isCharging = charging)
        }
}

/** Must be called inside a transaction. */
fun DataSnapshot.toModel() = SnapshotModel(
    id = id.value,
    deviceId = device.id.value,
    createdAt = createdAt,
    insertedAt = insertedAt,
    latitude = latitude,
    longitude = longitude,
    locationAccuracy = locationAccuracy,
    bearing = bearing,
    bearingAccuracy = bearingAccuracy,
    batteryLevel = batteryLevel,
    batteryCharging = batteryCharging,
    isRaw = isRaw,
)

/**
 * The position as one point of a location history.
 *
 * [includeBattery] must reflect the caller's permission, not just data availability:
 * the device owner always sees the charge level, a share holder only when the share
 * opted in (see [forShare]).
 */
fun SnapshotModel.toHistoryPoint(includeBattery: Boolean) = LocationHistoryPoint(
    timestamp = createdAt.toEpochMilliseconds(),
    latitude = latitude,
    longitude = longitude,
    locationAccuracy = locationAccuracy,
    bearing = bearing,
    bearingAccuracy = bearingAccuracy,
    battery = if (includeBattery) {
        battery?.let { LocationHistoryPoint.Battery(it.percentage, it.isCharging) }
    } else null,
    isRaw = isRaw,
)
