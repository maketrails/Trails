package es.jvbabi.trails.data.event

import es.jvbabi.trails.data.model.DeviceDeletionModel
import es.jvbabi.trails.data.model.DeviceModel
import kotlin.uuid.Uuid

/**
 * What happened to one account's own things, as published by
 * [es.jvbabi.trails.data.UserRepository.events].
 *
 * The per-account counterpart to [DeviceEvent]: a device's own stream is the right
 * place for a subscriber that watches *that* device, while these are the changes to
 * *which* things a user has — the ones a client showing a list has to hear about
 * without knowing the members in advance.
 */
sealed interface UserEvent {
    val userId: Uuid

    /** A device of this user was added or changed. */
    data class DeviceChanged(override val userId: Uuid, val device: DeviceModel) : UserEvent

    /** A device of this user was removed. */
    data class DeviceRemoved(override val userId: Uuid, val deletion: DeviceDeletionModel) : UserEvent

    /**
     * The shares this user saved from others changed. [activeShareId] is the
     * redemption that came or went.
     */
    data class SavedSharesChanged(override val userId: Uuid, val activeShareId: Uuid) : UserEvent

    /**
     * A share this user emitted changed — its settings, or a redemption of it.
     * [shareId] is the share, not a redemption.
     */
    data class EmittedSharesChanged(override val userId: Uuid, val shareId: Uuid) : UserEvent

    /**
     * How far the optimizer has got on one of this user's devices. Published per
     * account rather than per device because the one client that shows it follows
     * every device at once.
     */
    data class OptimizationProgressed(
        override val userId: Uuid,
        val deviceId: Uuid,
        val progress: Double,
        val isRunning: Boolean,
    ) : UserEvent
}
