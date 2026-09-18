package es.jvbabi.trails.api.v1.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which generation of the optimized track a device currently holds.
 *
 * [rebuiltAt] is when the track was last thrown away and derived again from scratch,
 * in epoch **milliseconds**, or `null` if it never was. A client that cached the
 * optimized track under a different value holds a stale one and has to read it again
 * in full — continuing from its cursor only covers a track that was extended.
 */
@Serializable
data class TrackGenerationResponse(
    @SerialName("rebuilt_at") val rebuiltAt: Long?,
)
