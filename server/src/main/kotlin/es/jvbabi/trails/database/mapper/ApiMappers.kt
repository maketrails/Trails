package es.jvbabi.trails.database.mapper

import database.DataSnapshot
import es.jvbabi.trails.api.v1.history.LocationHistoryPoint
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Share
import es.jvbabi.trails.database.User
import kotlin.math.roundToInt
import es.jvbabi.trails.api.v1.entity.ActiveShare as ActiveShareEntity
import es.jvbabi.trails.api.v1.entity.Device as DeviceEntity
import es.jvbabi.trails.api.v1.entity.Share as ShareEntity
import es.jvbabi.trails.api.v1.entity.User as UserEntity

/**
 * Wire-entity mappers. All of them access relations (owner/device/share) and must
 * therefore be called inside a [es.jvbabi.trails.database.DatabaseManager.transaction].
 */

fun Device.toApi() = DeviceEntity(
    id = id.value,
    manufacturer = manufacturer,
    model = model,
    friendlyName = friendlyName,
    displayName = displayName,
    ownerId = owner.id.value,
)

fun User.toApi() = UserEntity(
    id = id.value,
    username = username,
)

fun Share.toApi() = ShareEntity(
    id = id.value,
    deviceId = device.id.value,
    shareName = shareName,
    locationHistorySeconds = locationHistorySeconds,
    shareBatteryState = shareBatteryState,
    allowMultiuse = allowMultiuse,
    isLocked = isLocked,
)

fun ActiveShare.toApi() = ActiveShareEntity(
    id = id.value,
    shareId = share.id.value,
)

/**
 * Maps a stored snapshot into a history point.
 *
 * [includeBattery] must reflect the caller's permission, not just data
 * availability: the device owner always sees the battery state, a share holder
 * only when the share opted in via `share_battery_state`.
 */
fun DataSnapshot.toHistoryPoint(includeBattery: Boolean) = LocationHistoryPoint(
    timestamp = createdAt.toEpochMilliseconds(),
    latitude = latitude,
    longitude = longitude,
    locationAccuracy = locationAccuracy,
    bearing = bearing,
    bearingAccuracy = bearingAccuracy,
    battery = if (includeBattery) {
        val level = batteryLevel
        val charging = batteryCharging
        if (level != null && charging != null) {
            LocationHistoryPoint.Battery((level * 100).roundToInt(), charging)
        } else null
    } else null,
    isRaw = isRaw,
)
