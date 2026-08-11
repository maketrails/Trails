package es.jvbabi.trails.api.v1.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How far the track of one of the caller's own devices has been optimized.
 *
 * [optimizedPoints] and [unoptimizedPoints] are disjoint: what the optimizer has
 * produced, and the measurements behind the point it has reached.
 * [rawPoints] is the whole measured series instead, so it overlaps both — it is
 * there to compare against: a clean track holds far fewer positions and runs a
 * shorter distance than the measurements it came from, and the difference is the
 * jitter that was removed.
 *
 * [progress] only counts positions old enough to be optimized at all — the
 * newest minutes are held back on purpose — so a device that is fully caught up
 * reports 1.0 even though [unoptimizedPoints] is not zero.
 */
@Serializable
data class DeviceOptimizationResponse(
    @SerialName("optimized_points") val optimizedPoints: Long,
    @SerialName("unoptimized_points") val unoptimizedPoints: Long,
    @SerialName("raw_points") val rawPoints: Long,
    @SerialName("optimized_distance_meters") val optimizedDistanceMeters: Double,
    @SerialName("unoptimized_distance_meters") val unoptimizedDistanceMeters: Double,
    @SerialName("raw_distance_meters") val rawDistanceMeters: Double,
    @SerialName("progress") val progress: Double,
    @SerialName("is_running") val isRunning: Boolean,
)
