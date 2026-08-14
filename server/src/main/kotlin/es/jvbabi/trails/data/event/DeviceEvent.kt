package es.jvbabi.trails.data.event

import es.jvbabi.trails.data.model.DeviceDeletionModel
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.data.model.SnapshotModel
import es.jvbabi.trails.shared.dto.websocket.PingSource
import kotlin.uuid.Uuid

/**
 * Everything that can happen to one device, as published by
 * [es.jvbabi.trails.data.DeviceRepository.events].
 *
 * One event type per thing that happens, not per consumer: a subscriber filters
 * for what it renders. Requests at a device (ping, ring) and the device's own
 * reports (ring state) are separate cases on purpose — a request is aimed at the
 * device and only its own connection acts on it, while a report is what every
 * watching UI may believe.
 *
 * Carrying the changed model rather than an id keeps a subscriber from having to
 * read the device back, and makes what it renders the state at the time of the
 * event instead of whatever it happens to find later.
 */
sealed interface DeviceEvent {
    val deviceId: Uuid

    /** The device's stored data changed — a rename, for instance. */
    data class Changed(val device: DeviceModel) : DeviceEvent {
        override val deviceId: Uuid get() = device.id
    }

    /** The device was removed. Its data is soft-deleted, so this is terminal. */
    data class Deleted(val deletion: DeviceDeletionModel) : DeviceEvent {
        override val deviceId: Uuid get() = deletion.deviceId
    }

    /** A new position (and charge level) was stored for the device. */
    data class SnapshotAdded(val snapshot: SnapshotModel) : DeviceEvent {
        override val deviceId: Uuid get() = snapshot.deviceId
    }

    /** Someone asked the device to report back once, so it can be located. */
    data class PingRequested(
        override val deviceId: Uuid,
        val requestedByName: String,
        val requestedBySource: PingSource,
    ) : DeviceEvent

    /** Someone asked the device to start ringing. */
    data class RingRequested(
        override val deviceId: Uuid,
        val requestedByName: String,
    ) : DeviceEvent

    /** Someone asked the device to stop ringing. */
    data class RingStopRequested(override val deviceId: Uuid) : DeviceEvent

    /**
     * The device itself confirmed it started or stopped ringing. This — not
     * [RingRequested] — is what a UI may show, so every client agrees with the
     * device rather than with whoever asked.
     */
    data class RingStateChanged(
        override val deviceId: Uuid,
        val isRinging: Boolean,
        val requestedByName: String,
    ) : DeviceEvent
}
