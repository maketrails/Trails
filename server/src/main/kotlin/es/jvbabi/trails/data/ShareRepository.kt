package es.jvbabi.trails.data

import es.jvbabi.trails.data.event.ActiveShareEvent
import es.jvbabi.trails.data.event.DeviceEvent
import es.jvbabi.trails.data.event.ShareEvent
import es.jvbabi.trails.data.event.UserEvent
import es.jvbabi.trails.data.model.ActiveShareModel
import es.jvbabi.trails.data.model.ShareModel
import es.jvbabi.trails.data.model.SharedDeviceModel
import es.jvbabi.trails.data.model.UserShareModel
import es.jvbabi.trails.data.model.forShare
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.ActiveShares
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import es.jvbabi.trails.database.Share
import es.jvbabi.trails.database.Shares
import es.jvbabi.trails.database.User
import es.jvbabi.trails.database.UserShare
import es.jvbabi.trails.database.UserShares
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/** Why a share could not be changed, or that it was. */
sealed interface ShareUpdateResult {
    data class Updated(val share: ShareModel) : ShareUpdateResult
    data object NotAllowed : ShareUpdateResult
    data object NameTaken : ShareUpdateResult
    data object NameEmpty : ShareUpdateResult
}

/** How redeeming a share went. */
sealed interface ShareRedeemResult {
    data class Success(val activeShare: ActiveShareModel) : ShareRedeemResult
    /** A single-use share that has already been spent. */
    data object Locked : ShareRedeemResult
    data object NotFound : ShareRedeemResult
}

/**
 * The shares: the ones a user emits, the redemptions handed out for them, and the
 * ones a user saved from somebody else.
 *
 * Where [DeviceRepository] owns a device, this owns the permission to watch one —
 * and it *subscribes* to the device to serve that permission: a holder of a
 * redemption never sees the device's own stream, only [activeShareEvents], which
 * forwards the part a share hands out. That filtering lives here, so no consumer has
 * to know the rules or be trusted to apply them.
 */
class ShareRepository : KoinComponent {
    private val db by inject<DatabaseManager>()
    private val deviceRepository by inject<DeviceRepository>()
    private val userRepository by inject<UserRepository>()

    private val events = mutableMapOf<Uuid, MutableSharedFlow<ShareEvent>>()
    private val eventsMutex = Mutex()

    // --- emitted shares ---------------------------------------------------

    suspend fun getById(shareId: Uuid): ShareModel? =
        db.transaction { Share.findById(shareId)?.toModel() }

    /** Every share this user emitted, whether or not its device still exists. */
    suspend fun listEmittedBy(ownerId: Uuid): List<ShareModel> =
        db.transaction {
            Share.wrapRows(
                Shares
                    .innerJoin(Devices)
                    .select(Shares.columns)
                    .where { Devices.owner eq ownerId }
            ).map { it.toModel() }
        }

    /**
     * Whether this user already emitted a share called [shareName]. [exceptShareId]
     * leaves one share out, so renaming a share to the name it already has is not a
     * conflict with itself.
     */
    suspend fun existsWithName(ownerId: Uuid, shareName: String, exceptShareId: Uuid? = null): Boolean =
        db.transaction {
            Shares
                .innerJoin(Devices)
                .select(Shares.id)
                .where { Devices.owner eq ownerId }
                .andWhere { Shares.shareName eq shareName }
                .apply { exceptShareId?.let { andWhere { Shares.id neq it } } }
                .count() > 0
        }

    /**
     * Emits a new share for [deviceId]. Returns `null` when the device is gone; the
     * caller checks the name first, since a taken name is its own answer.
     */
    suspend fun create(
        deviceId: Uuid,
        shareName: String,
        locationHistorySeconds: Int,
        allowMultiuse: Boolean,
        shareBatteryState: Boolean,
    ): ShareModel? {
        val share = db.transaction {
            val device = Device.findById(deviceId) ?: return@transaction null
            Share.new {
                this.device = device
                this.shareName = shareName
                this.locationHistorySeconds = locationHistorySeconds
                this.allowMultiuse = allowMultiuse
                this.shareBatteryState = shareBatteryState
                this.isLocked = false
            }.stored().toModel()
        } ?: return null

        announceChange(share)
        return share
    }

    /**
     * Changes the settings of a share [ownerId] emitted. Only the values given are
     * touched, and everything is validated before the first write — a late rejection
     * would leave the earlier fields applied.
     *
     * The change reaches everyone who already redeemed the share: the terms live on
     * the share, not on a redemption.
     */
    suspend fun update(
        shareId: Uuid,
        ownerId: Uuid,
        shareName: String? = null,
        locationHistorySeconds: Int? = null,
        shareBatteryState: Boolean? = null,
    ): ShareUpdateResult {
        val result = db.transaction {
            val share = Share.findById(shareId) ?: return@transaction ShareUpdateResult.NotAllowed
            val device = share.device
            if (device.owner.id.value != ownerId) return@transaction ShareUpdateResult.NotAllowed
            if (device.deletion != null) return@transaction ShareUpdateResult.NotAllowed

            val requestedName = shareName?.trim()
            if (requestedName != null) {
                if (requestedName.isEmpty()) return@transaction ShareUpdateResult.NameEmpty
                val isNameTaken = Shares
                    .innerJoin(Devices)
                    .select(Shares.id)
                    .where { Devices.owner eq device.owner.id }
                    .andWhere { Shares.shareName eq requestedName }
                    .andWhere { Shares.id neq share.id }
                    .count() > 0
                if (isNameTaken) return@transaction ShareUpdateResult.NameTaken
            }

            locationHistorySeconds?.let { share.locationHistorySeconds = it }
            shareBatteryState?.let { share.shareBatteryState = it }
            requestedName?.let { share.shareName = it }

            ShareUpdateResult.Updated(share.toModel())
        }

        if (result is ShareUpdateResult.Updated) announceChange(result.share)
        return result
    }

    /**
     * Deletes a share [ownerId] emitted, for real: its redemptions go with it, so
     * every link handed out stops working. Returns false when the share is not
     * theirs or already gone.
     *
     * Saved references to those redemptions that live on this server are removed too,
     * and their owners told — a reference on a foreign homeserver cannot be touched
     * from here, and those clients reconcile against this server the usual way.
     */
    suspend fun delete(shareId: Uuid, ownerId: Uuid): Boolean {
        val affectedSavers = db.transaction {
            val share = Share.findById(shareId) ?: return@transaction null
            if (share.device.owner.id.value != ownerId) return@transaction null

            val activeShareIds = ActiveShare.find { ActiveShares.share eq share.id }.map { it.id.value }

            // Remembered before the delete so their owners can be told afterwards.
            val savers = activeShareIds.flatMap { activeShareId ->
                UserShare.find { UserShares.shareId eq activeShareId }.toList().map { userShare ->
                    userShare.user.id.value to activeShareId
                }
            }
            savers.forEach { (userId, activeShareId) ->
                UserShare.find { (UserShares.user eq userId) and (UserShares.shareId eq activeShareId) }
                    .forEach { it.delete() }
            }

            // Cascades to the share's redemptions.
            share.delete()
            savers
        } ?: return false

        publish(ShareEvent.Deleted(shareId))
        userRepository.publish(UserEvent.EmittedSharesChanged(userId = ownerId, shareId = shareId))
        affectedSavers.forEach { (userId, activeShareId) ->
            userRepository.publish(UserEvent.SavedSharesChanged(userId = userId, activeShareId = activeShareId))
        }
        return true
    }

    // --- redemptions ------------------------------------------------------

    suspend fun getActiveShareById(activeShareId: Uuid): ActiveShareModel? =
        db.transaction { ActiveShare.findById(activeShareId)?.toModel() }

    /** The redemptions of a share, newest first — the order a list of them is shown in. */
    suspend fun listRedemptionsOf(shareId: Uuid): List<ActiveShareModel> =
        db.transaction {
            ActiveShare
                .find { ActiveShares.share eq shareId }
                .orderBy(ActiveShares.createdAt to SortOrder.DESC)
                .map { it.toModel() }
        }

    /**
     * Redeems a share, which hands out one redemption. A share that does not allow
     * multiple use locks itself in the same transaction, so a link cannot be redeemed
     * twice by two callers arriving together.
     */
    suspend fun redeem(shareId: Uuid): ShareRedeemResult {
        // The share comes back alongside the outcome because announcing the
        // redemption needs it, and it must be the state from inside the transaction
        // that locked it.
        val (result, share) = db.transaction {
            val entity = Share.findById(shareId)
                ?: return@transaction ShareRedeemResult.NotFound to null
            if (entity.isLocked) return@transaction ShareRedeemResult.Locked to null

            val activeShare = ActiveShare.new { this.share = entity }
            if (!entity.allowMultiuse) entity.isLocked = true

            ShareRedeemResult.Success(activeShare.stored().toModel()) to entity.toModel()
        }

        if (result is ShareRedeemResult.Success && share != null) {
            publish(ShareEvent.Redeemed(shareId = share.id, activeShare = result.activeShare))
            userRepository.publish(UserEvent.EmittedSharesChanged(userId = share.ownerId, shareId = share.id))
        }
        return result
    }

    /**
     * Gives a redemption back. Idempotent: one that is already gone is still a
     * success.
     *
     * Deliberately does not unlock a spent single-use share — the link must not
     * become redeemable again.
     */
    suspend fun returnActiveShare(activeShareId: Uuid): Boolean {
        val share = db.transaction {
            val activeShare = ActiveShare.findById(activeShareId) ?: return@transaction null
            val share = activeShare.share.toModel()
            activeShare.delete()
            share
        } ?: return false

        publish(ShareEvent.Returned(shareId = share.id, activeShareId = activeShareId))
        userRepository.publish(UserEvent.EmittedSharesChanged(userId = share.ownerId, shareId = share.id))
        return true
    }

    /**
     * Which of [activeShareIds] still exist here — present, and their device not
     * removed. Lets a client reconcile a list of saved capabilities in one ask.
     */
    suspend fun filterExistingActiveShares(activeShareIds: List<Uuid>): List<Uuid> =
        db.transaction {
            activeShareIds.filter { id ->
                val activeShare = ActiveShare.findById(id) ?: return@filter false
                activeShare.share.device.deletion == null
            }
        }

    /**
     * Everything a redemption stands for, or `null` when it is gone — including when
     * its device was removed, which from a holder's side is the same thing.
     */
    suspend fun getSharedDevice(activeShareId: Uuid): SharedDeviceModel? =
        db.transaction {
            val activeShare = ActiveShare.findById(activeShareId) ?: return@transaction null
            val share = activeShare.share
            val device = share.device
            if (device.deletion != null) return@transaction null

            SharedDeviceModel(
                activeShare = activeShare.toModel(),
                share = share.toModel(),
                device = device.toModel(),
                ownerUsername = device.owner.username,
            )
        }

    // --- saved shares -----------------------------------------------------

    /** The shares this user saved from others, foreign homeservers included. */
    suspend fun listSavedBy(userId: Uuid): List<UserShareModel> =
        db.transaction { UserShare.find { UserShares.user eq userId }.map { it.toModel() } }

    /**
     * Saves a redemption to the user's account so it survives a reinstall. Doing it
     * twice changes nothing.
     */
    suspend fun save(userId: Uuid, activeShareId: Uuid, homeserver: String) {
        val saved = db.transaction {
            val user = User.findById(userId) ?: return@transaction false
            val alreadySaved = !UserShare.find {
                (UserShares.user eq userId) and
                        (UserShares.shareId eq activeShareId) and
                        (UserShares.homeserver eq homeserver)
            }.empty()
            if (alreadySaved) return@transaction false

            UserShare.new {
                this.user = user
                this.shareId = activeShareId
                this.homeserver = homeserver
            }
            true
        }

        if (saved) {
            userRepository.publish(UserEvent.SavedSharesChanged(userId = userId, activeShareId = activeShareId))
        }
    }

    /**
     * Drops the account's reference to a saved share. This is only the account half
     * of giving one back — the redemption on its origin homeserver is a separate,
     * direct call to that server (see [returnActiveShare]).
     */
    suspend fun removeSaved(userId: Uuid, activeShareId: Uuid, homeserver: String): Boolean {
        val removed = db.transaction {
            val matches = UserShare.find {
                (UserShares.user eq userId) and
                        (UserShares.shareId eq activeShareId) and
                        (UserShares.homeserver eq homeserver)
            }.toList()
            matches.forEach { it.delete() }
            matches.isNotEmpty()
        }

        if (removed) {
            userRepository.publish(UserEvent.SavedSharesChanged(userId = userId, activeShareId = activeShareId))
        }
        return removed
    }

    // --- events -----------------------------------------------------------

    /** The share's own stream, for its owner: settings, redemptions, deletion. */
    suspend fun events(shareId: Uuid): SharedFlow<ShareEvent> = flowFor(shareId)

    /**
     * What one redemption reveals over time — this is the share subscribing to its
     * device.
     *
     * The device's positions are forwarded only as far as the share's settings allow,
     * and the settings are followed live off the share's own stream rather than read
     * once: turning the battery state off has to take effect on the next position, not
     * on the next reconnect.
     *
     * Ends with [ActiveShareEvent.Gone] and nothing after it, so a subscriber can
     * treat that as the close signal it is.
     */
    suspend fun activeShareEvents(activeShareId: Uuid): Flow<ActiveShareEvent> {
        val context = getSharedDevice(activeShareId)
            ?: return flowOf(ActiveShareEvent.Gone(wasDeviceRemoved = false))

        return channelFlow {
            val settings = MutableStateFlow(context.share)

            launch {
                events(context.share.id).collect { event ->
                    when (event) {
                        is ShareEvent.Changed -> {
                            settings.value = event.share
                            send(ActiveShareEvent.SettingsChanged(event.share))
                        }
                        is ShareEvent.Deleted -> {
                            send(ActiveShareEvent.Gone(wasDeviceRemoved = false))
                            close()
                        }
                        is ShareEvent.Returned -> if (event.activeShareId == activeShareId) {
                            send(ActiveShareEvent.Gone(wasDeviceRemoved = false))
                            close()
                        }
                        // Another holder redeeming the same share is none of this
                        // holder's business.
                        is ShareEvent.Redeemed -> {}
                    }
                }
            }

            launch {
                deviceRepository.events(context.device.id).collect { event ->
                    when (event) {
                        is DeviceEvent.SnapshotAdded -> send(
                            ActiveShareEvent.SnapshotAdded(event.snapshot.forShare(settings.value))
                        )
                        is DeviceEvent.Deleted -> {
                            send(ActiveShareEvent.Gone(wasDeviceRemoved = true))
                            close()
                        }
                        is DeviceEvent.OnlineStateChanged ->
                            send(ActiveShareEvent.OnlineStateChanged(event.isOnline))
                        // A rename, a ring, a ping: the device's own affairs, and
                        // nothing a share hands out.
                        else -> {}
                    }
                }
            }

            awaitClose()
        }
    }

    /**
     * Publishes a share event. Called by this repository; nothing outside
     * [es.jvbabi.trails.data] announces a change it did not persist.
     */
    suspend fun publish(event: ShareEvent) {
        flowFor(event.shareId).emit(event)
    }

    private suspend fun flowFor(shareId: Uuid): MutableSharedFlow<ShareEvent> =
        eventsMutex.withLock { events.getOrPut(shareId) { MutableSharedFlow(
            // Buffered and dropping, so publishing never waits on a subscriber. An
            // unbuffered flow suspends the *publisher* until every collector has taken
            // the value — one webapp socket busy re-sending its list (reverse geocoding
            // included) would hold up the device going offline for everyone else.
            //
            // Dropping is safe because these are signals, not a log: every consumer
            // re-reads the current state when it hears one, so a dropped event costs at
            // most one redundant re-send, and the next one still carries the truth.
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        ) } }

    private suspend fun announceChange(share: ShareModel) {
        publish(ShareEvent.Changed(share))
        userRepository.publish(UserEvent.EmittedSharesChanged(userId = share.ownerId, shareId = share.id))
    }
}
