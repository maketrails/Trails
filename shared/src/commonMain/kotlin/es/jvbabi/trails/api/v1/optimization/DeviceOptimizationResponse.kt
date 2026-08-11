package es.jvbabi.trails.api.v1.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How far the track of one of the caller's own devices has been optimized.
 *
 * The two point counts are disjoint and not comparable in size: the optimizer
 * drops the positions it cannot trust and collapses standstills, so a clean
 * track has far fewer positions than the measurements it came from.
 *
 * [progress] only counts positions old enough to be optimized at all — the
 * newest minutes are held back on purpose — so a device that is fully caught up
 * reports 1.0 even though [unoptimizedPoints] is not zero.
 */
@Serializable
data class DeviceOptimizationResponse(
    @SerialName("optimized_points") val optimizedPoints: Long,
    @SerialName("unoptimized_points") val unoptimizedPoints: Long,
    @SerialName("optimized_distance_meters") val optimizedDistanceMeters: Double,
    @SerialName("unoptimized_distance_meters") val unoptimizedDistanceMeters: Double,
    @SerialName("progress") val progress: Double,
    @SerialName("is_running") val isRunning: Boolean,
)
