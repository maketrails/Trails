package es.jvbabi.trails.api.v1.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A device's recorded location history, oldest point first.
 *
 * [historySeconds] reports the retention window the server actually applied:
 * `null` means nothing was cut off (the caller owns the device, or the share it
 * holds carries an unbounded window), any other value is the share's
 * `location_history_seconds` that capped the response.
 */
@Serializable
data class LocationHistoryResponse(
    @SerialName("history_seconds") val historySeconds: Int? = null,
    @SerialName("points") val points: List<LocationHistoryPoint> = emptyList(),
)

/**
 * One recorded position. [timestamp] is epoch **milliseconds**, matching the
 * `found_at` field of the snapshot endpoints.
 *
 * [battery] is only present when the caller is allowed to see the battery state
 * (always for the device owner, for a share only when it opted in) *and* the
 * device actually reported it.
 *
 * [isRaw] tells the two halves of a track apart: the optimized positions the
 * server derived, and the raw measurements behind them that no optimizer has
 * reached yet. Consumers draw the raw tail differently instead of pretending the
 * whole track is equally trustworthy.
 */
@Serializable
data class LocationHistoryPoint(
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
    @SerialName("location_accuracy") val locationAccuracy: Double,
    @SerialName("bearing") val bearing: Double,
    @SerialName("bearing_accuracy") val bearingAccuracy: Double?,
    @SerialName("battery") val battery: Battery?,
    @SerialName("is_raw") val isRaw: Boolean = true,
) {
    @Serializable
    data class Battery(
        @SerialName("percentage") val percentage: Int,
        @SerialName("is_charging") val isCharging: Boolean,
    )
}
