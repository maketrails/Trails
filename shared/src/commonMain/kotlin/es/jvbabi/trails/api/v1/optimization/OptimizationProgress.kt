package es.jvbabi.trails.api.v1.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How far the optimizer has got on a device, and whether a run is in progress.
 *
 * [progress] is the share of the settled raw positions the optimized track covers,
 * 0..1. It only counts positions old enough to be optimized at all, so a device that
 * is fully caught up reports 1.0 even while its newest minutes are still raw.
 */
@Serializable
sealed class OptimizationProgress {
    abstract val progress: Double

    @Serializable
    @SerialName("idle")
    data class Idle(
        @SerialName("progress") override val progress: Double,
    ) : OptimizationProgress()

    @Serializable
    @SerialName("running")
    data class Running(
        @SerialName("progress") override val progress: Double,
    ) : OptimizationProgress()
}
