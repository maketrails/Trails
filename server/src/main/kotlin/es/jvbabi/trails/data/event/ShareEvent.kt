package es.jvbabi.trails.data.event

import es.jvbabi.trails.data.model.ActiveShareModel
import es.jvbabi.trails.data.model.ShareModel
import es.jvbabi.trails.data.model.SnapshotModel
import kotlin.uuid.Uuid

/**
 * What can happen to a share its owner emitted, as published by
 * [es.jvbabi.trails.data.ShareRepository.events].
 *
 * This is the owner's view — the settings and the redemptions. What a *holder* of
 * a redemption gets to see is [ActiveShareEvent].
 */
sealed interface ShareEvent {
    val shareId: Uuid

    /** The share's settings changed, or it locked itself after a redemption. */
    data class Changed(val share: ShareModel) : ShareEvent {
        override val shareId: Uuid get() = share.id
    }

    /** The share was redeemed; [activeShare] is the redemption that was created. */
    data class Redeemed(override val shareId: Uuid, val activeShare: ActiveShareModel) : ShareEvent

    /** A redemption was given back. */
    data class Returned(override val shareId: Uuid, val activeShareId: Uuid) : ShareEvent

    /** The share was deleted, which invalidates every redemption of it. */
    data class Deleted(override val shareId: Uuid) : ShareEvent
}

/**
 * What one redemption reveals over time, as published by
 * [es.jvbabi.trails.data.ShareRepository.activeShareEvents].
 *
 * This is where a share subscribes to its device: the repository watches the
 * shared device's [DeviceEvent]s and forwards only what the share's settings
 * allow, so no consumer has to know the rules — or be trusted to apply them.
 */
sealed interface ActiveShareEvent {
    /** A new position the share is allowed to reveal. */
    data class SnapshotAdded(val snapshot: SnapshotModel) : ActiveShareEvent

    /** The share's settings changed, so what it reveals from here on may differ. */
    data class SettingsChanged(val share: ShareModel) : ActiveShareEvent

    /**
     * The shared device gained or lost its connection.
     *
     * Part of what a share reveals: a holder seeing a position needs to know whether
     * it is current or the last thing known. Unconditional — unlike the charge level,
     * this is not something a share opts into.
     */
    data class OnlineStateChanged(val isOnline: Boolean) : ActiveShareEvent

    /**
     * The redemption stopped existing: it was given back, its share was deleted, or
     * the shared device was removed. Terminal, and nothing follows it.
     *
     * [wasDeviceRemoved] separates the one case a holder can act on — the device is
     * gone for everyone, so no new share of it will help — from a share that was
     * simply taken back. Nothing beyond that is said about the owner's reasons.
     */
    data class Gone(val wasDeviceRemoved: Boolean) : ActiveShareEvent
}
