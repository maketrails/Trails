package es.jvbabi.trails.data.model

/**
 * A charge level, as it is shown to a user.
 *
 * Derived from a [SnapshotModel] rather than stored on its own — a device reports its
 * battery together with a position, never apart from one.
 */
data class BatteryModel(
    val percentage: Int,
    val isCharging: Boolean,
)
