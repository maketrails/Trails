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
 * [rebuiltAt] is when the track was last thrown away and derived again from
 * scratch, in epoch **milliseconds**, or `null` if it never was. A client that
 * cached the optimized track under a different value holds a stale one and has to
 * read it again in full — continuing from its cursor only covers a track that was
 * extended.
 */
@Serializable
data class DeviceOptimizationResponse(
    @SerialName("optimized_points") val optimizedPoints: Long,
    @SerialName("unoptimized_points") val unoptimizedPoints: Long,
    @SerialName("raw_points") val rawPoints: Long,
    @SerialName("optimized_distance_meters") val optimizedDistanceMeters: Double,
    @SerialName("unoptimized_distance_meters") val unoptimizedDistanceMeters: Double,
    @SerialName("raw_distance_meters") val rawDistanceMeters: Double,
    @SerialName("rebuilt_at") val rebuiltAt: Long?,
    @SerialName("state") val state: OptimizationProgress,
)
