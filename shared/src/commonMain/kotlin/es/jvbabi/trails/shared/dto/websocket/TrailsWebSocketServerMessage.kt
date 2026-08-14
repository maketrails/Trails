package es.jvbabi.trails.shared.dto.websocket

import es.jvbabi.trails.shared.dto.DeviceResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Where a device ping originated. Lets the receiving device render an
 * appropriate notification text — a named device versus an anonymous browser
 * session (which has no device identity).
 */
@Serializable
enum class PingSource {
    @SerialName("device") DEVICE,
    @SerialName("browser") BROWSER,
}

@Serializable
sealed class TrailsWebSocketServerMessage {
    @Serializable
    @SerialName("device.deleted")
    data class DeviceDeleted(
        @SerialName("deleted_by_device_name") val deletedByDeviceName: String,
        @SerialName("device_id") val deviceId: String,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.updated")
    data class DeviceUpdated(
        @SerialName("data") val data: DeviceResponse,
    ) : TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("share.deleted")
    data class ShareDeleted(
        @SerialName("was_device_removed") val wasDeviceRemoved: Boolean,
        @SerialName("share_id") val shareId: String,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("snapshot.acknowledged")
    data class SnapshotAcknowledged(
        @SerialName("snapshot_id") val snapshotId: Uuid,
    ) : TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("share.snapshot")
    data class Snapshot(
        @SerialName("snapshot_id") val snapshotId: Uuid,
        @SerialName("target") val target: Target,
        @SerialName("timestamp") val timestamp: Long,
        @SerialName("location") val location: Location,
        @SerialName("battery_state") val batteryState: BatteryState?,
    ) : TrailsWebSocketServerMessage() {

        @Serializable
        sealed class Target {
            @Serializable
            @SerialName("share")
            data class Share(@SerialName("id") val shareId: String) : Target()

            @Serializable
            @SerialName("device")
            data class Device(@SerialName("id") val deviceId: String) : Target()
        }

        @Serializable
        data class Location(
            @SerialName("latitude") val latitude: Double,
            @SerialName("longitude") val longitude: Double,
            @SerialName("bearing") val bearing: Float,
            @SerialName("bearing_accuracy") val bearingAccuracy: Float?,
            @SerialName("location_accuracy") val locationAccuracy: Float,
        )

        @Serializable
        data class BatteryState(
            @SerialName("percentage") val percentage: Int,
            @SerialName("is_charging") val isCharging: Boolean,
        )
    }

    /**
     * Whether a device is reachable, and since when.
     *
     * Addressed like a position — the same device or redemption a [Snapshot] is about
     * — so a client that already knows how to route one knows how to route this.
     *
     * [since] is when the state began, in epoch millis, or null when that is not
     * known: presence is held in memory on the server, so a device that has not
     * connected since the server started is offline without a since.
     */
    @Serializable
    @SerialName("device.online_state")
    data class OnlineState(
        @SerialName("target") val target: Snapshot.Target,
        @SerialName("is_online") val isOnline: Boolean,
        @SerialName("since") val since: Long?,
    ) : TrailsWebSocketServerMessage()

    /**
     * Asks the device whether it is still there, answered with
     * [TrailsWebSocketAppMessage.HeartbeatAck].
     *
     * Deliberately an application message and not a WebSocket ping: a proxy or tunnel
     * in front of the server answers control frames itself, so a device long gone can
     * look perfectly alive. This one only the app can answer.
     *
     * Nothing to do with [Ping], which asks the *user's* device to make itself heard
     * and shows a notification. This one is invisible and is only about the
     * connection.
     */
    @Serializable
    @SerialName("connection.heartbeat")
    data object Heartbeat : TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.ping")
    data class Ping(
        @SerialName("pinged_by_device_name") val pingedByDeviceName: String,
        @SerialName("pinged_by_source") val pingedBySource: PingSource = PingSource.DEVICE,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.ring")
    data class Ring(
        @SerialName("ringed_by_device_name") val ringedByDeviceName: String,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.ping.result")
    data class PingResult(
        @SerialName("device_id") val deviceId: String,
        @SerialName("success") val success: Boolean,
        @SerialName("has_delivered_notification") val hasDeliveredNotification: Boolean = false,
        @SerialName("error_message") val errorMessage: String? = null,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.ring.state")
    data class RingState(
        @SerialName("device_id") val deviceId: String,
        @SerialName("is_ringing") val isRinging: Boolean,
        @SerialName("ringed_by_device_name") val ringedByDeviceName: String,
    ): TrailsWebSocketServerMessage()

    @Serializable
    @SerialName("device.ring.stop")
    data object RingStop: TrailsWebSocketServerMessage()
}
