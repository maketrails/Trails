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
 *
 * [cursor] is how a caller reads incrementally: hand it back as `?since=` and the
 * next answer holds what has been *stored* in the meantime. That is not the same as
 * "recorded in the meantime" — the optimizer writes positions under the timestamps of
 * the measurements they came from, so a rebuilt stretch of the optimized track is new
 * data under old timestamps, and only a storage-time cursor catches it.
 *
 * An answer is merged into what the caller holds, by timestamp — a position uploaded
 * late comes back under an old timestamp without the newer ones around it. Only the
 * optimized positions replace: a rebuild can leave *fewer* of them, so every held
 * optimized position from the first returned optimized one onwards is superseded, and
 * so is every held raw position up to the last returned optimized one.
 *
 * [remaining] is only set for a `?chunked=true` read: how many points of the same
 * read come after this chunk. The next chunk continues with `?after=` set to the
 * timestamp of the last point; `0` means the read is complete. All chunks of one read
 * together form the answer described above, and each reports the cursor of its own
 * rows — the read continues from the largest of them.
 */
@Serializable
data class LocationHistoryResponse(
    @SerialName("history_seconds") val historySeconds: Int? = null,
    @SerialName("cursor") val cursor: Long? = null,
    @SerialName("points") val points: List<LocationHistoryPoint> = emptyList(),
    @SerialName("remaining") val remaining: Long? = null,
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
