package es.jvbabi.trails.data

import es.jvbabi.trails.data.event.DeviceEvent
import es.jvbabi.trails.data.event.UserEvent
import es.jvbabi.trails.data.model.DeviceDeletionModel
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.data.model.toModel
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.DeviceDeletion
import es.jvbabi.trails.database.DeviceType
import es.jvbabi.trails.database.Devices
import es.jvbabi.trails.database.Session
import es.jvbabi.trails.database.User
import es.jvbabi.trails.shared.dto.websocket.PingSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** What a device answered to a ping, once it did. */
data class PingAck(
    val hasDeliveredNotification: Boolean,
)

/**
 * The devices: everything that reads or changes one, and the stream of what
 * happened to it.
 *
 * Every write publishes the matching [DeviceEvent] on the device's own stream and,
 * where a list changed, a [UserEvent] on its owner's — which is the point of
 * routing all of it through here. A caller cannot persist a change and forget to
 * announce it, and a subscriber cannot be told about something that was not
 * persisted.
 *
 * Some of a device's state is not in the database at all: who asked it to ring, and
 * which pings are still waiting for an answer. Both describe *this* process talking
 * to the device, and neither would survive a restart with any meaning, so they live
 * here in memory next to the stored state rather than in a store of their own.
 */
class DeviceRepository : KoinComponent {
    private val db by inject<DatabaseManager>()
    private val userRepository by inject<UserRepository>()

    private val events = mutableMapOf<Uuid, MutableSharedFlow<DeviceEvent>>()
    private val eventsMutex = Mutex()

    /** Who asked a device to ring, kept until it stops. */
    private val ringRequests = mutableMapOf<Uuid, String>()
    private val ringRequestsMutex = Mutex()

    /** Pings still waiting for the device to answer. */
    private val pendingPings = mutableMapOf<Uuid, CompletableDeferred<PingAck>>()
    private val pendingPingsMutex = Mutex()

    /**
     * How many open connections each device holds; a device with an entry is online.
     *
     * Counted rather than flagged: an app that reconnects can briefly hold the old
     * and the new connection at once, and closing the old one must not report the
     * device as offline while the new one is already live.
     */
    private val connections = mutableMapOf<Uuid, Int>()
    private val connectionsMutex = Mutex()

    companion object {
        /** How long a ping waits before it counts as unanswered. */
        val PING_TIMEOUT: Duration = 5.seconds
    }

    // --- reads ------------------------------------------------------------

    suspend fun getById(deviceId: Uuid): DeviceModel? =
        db.transaction { Device.findById(deviceId)?.toModel() }

    /**
     * A device [ownerId] owns, or `null` when it does not exist or is somebody
     * else's — the two are deliberately indistinguishable, so a caller answering
     * `null` cannot be used to probe for foreign device ids.
     *
     * A removed device is still returned; whether that is acceptable is the
     * caller's call, and [DeviceModel.isDeleted] says so.
     */
    suspend fun getOwnedById(deviceId: Uuid, ownerId: Uuid): DeviceModel? =
        db.transaction { Device.findById(deviceId)?.takeIf { it.owner.id.value == ownerId }?.toModel() }

    /** Every device [ownerId] still has, removed ones left out. */
    suspend fun listOwnedBy(ownerId: Uuid): List<DeviceModel> =
        db.transaction {
            Device.find { (Devices.owner eq ownerId) and (Devices.deletion eq null) }
                .map { it.toModel() }
        }

    /** Every device of this user that is the same make and model as the one signing in. */
    suspend fun listOwnedByModel(ownerId: Uuid, manufacturer: String, model: String): List<DeviceModel> =
        db.transaction {
            Device
                .find {
                    (Devices.owner eq ownerId) and
                            (Devices.manufacturer eq manufacturer) and
                            (Devices.model eq model) and
                            (Devices.deletion eq null)
                }
                .map { it.toModel() }
        }

    /**
     * Whether this user already has a device called [displayName]. Includes removed
     * devices, matching how the name is checked when one is linked.
     */
    suspend fun existsOwnedWithDisplayName(ownerId: Uuid, displayName: String): Boolean =
        db.transaction {
            !Device.find { (Devices.owner eq ownerId) and (Devices.displayName eq displayName) }.empty()
        }

    /** Every device that still exists, for the passes that walk all of them. */
    suspend fun listAll(): List<DeviceModel> =
        db.transaction { Device.find { Devices.deletion eq null }.map { it.toModel() } }

    // --- writes -----------------------------------------------------------

    /**
     * Links a new device to [ownerId] and announces it. Returns `null` if the
     * account is gone.
     */
    suspend fun create(
        ownerId: Uuid,
        manufacturer: String,
        model: String,
        friendlyName: String,
        displayName: String,
        type: DeviceType = DeviceType.Phone,
    ): DeviceModel? {
        val device = db.transaction {
            val owner = User.findById(ownerId) ?: return@transaction null
            Device.new {
                this.owner = owner
                this.manufacturer = manufacturer
                this.model = model
                this.friendlyName = friendlyName
                this.displayName = displayName
                this.type = type
            }.stored().toModel()
        } ?: return null

        announceChange(device)
        return device
    }

    /**
     * Renames a device of [ownerId]. A `null` or blank [displayName] puts the
     * model-derived name back (see [DeviceModel.defaultDisplayName]), which is how
     * a user clears a custom one.
     *
     * Returns the device as it now is, or `null` when it is not theirs.
     */
    suspend fun setDisplayName(deviceId: Uuid, ownerId: Uuid, displayName: String?): DeviceModel? {
        val device = db.transaction {
            val device = Device.findById(deviceId) ?: return@transaction null
            if (device.owner.id.value != ownerId) return@transaction null

            val requested = displayName?.trim()
            device.displayName =
                if (requested.isNullOrEmpty()) "${device.manufacturer} ${device.friendlyName}"
                else requested
            device.toModel()
        } ?: return null

        announceChange(device)
        return device
    }

    /**
     * Removes a device of [ownerId] by recording a deletion for it — the data stays,
     * so a track that was already handed out does not silently change. Returns
     * `null` when the device is not theirs or was already removed.
     *
     * [deletedBySessionId] is the session that asked; leave it out for a browser,
     * which has no device to name.
     */
    suspend fun delete(deviceId: Uuid, ownerId: Uuid, deletedBySessionId: Uuid? = null): DeviceDeletionModel? {
        val deletion = db.transaction {
            val device = Device.findById(deviceId) ?: return@transaction null
            if (device.owner.id.value != ownerId) return@transaction null
            if (device.deletion != null) return@transaction null

            val deletion = DeviceDeletion.new {
                this.device = device
                this.deletedBy = deletedBySessionId?.let { Session.findById(it) }
            }
            device.deletion = deletion
            deletion.stored().toModel()
        } ?: return null

        publish(DeviceEvent.Deleted(deletion))
        userRepository.publish(UserEvent.DeviceRemoved(userId = ownerId, deletion = deletion))
        return deletion
    }

    // --- presence ---------------------------------------------------------

    /**
     * Registers one open connection of a device and announces it as online if it is
     * its first.
     *
     * Presence lives in memory and nowhere else: it describes connections to *this*
     * process, and none of them survives a restart. Every device therefore counts as
     * offline right after a start and turns online again the moment its app
     * reconnects — there is nothing to reconcile with the database, and a crash
     * cannot leave a device marked online forever.
     */
    suspend fun connected(deviceId: Uuid) {
        val cameOnline = connectionsMutex.withLock {
            val count = connections[deviceId] ?: 0
            connections[deviceId] = count + 1
            count == 0
        }
        if (cameOnline) publish(DeviceEvent.OnlineStateChanged(deviceId, isOnline = true))
    }

    /**
     * Gives one connection back and announces the device as offline once its last one
     * is gone.
     */
    suspend fun disconnected(deviceId: Uuid) {
        val wentOffline = connectionsMutex.withLock {
            val count = connections[deviceId] ?: return@withLock false
            if (count > 1) {
                connections[deviceId] = count - 1
                false
            } else {
                connections.remove(deviceId)
                true
            }
        }
        if (wentOffline) publish(DeviceEvent.OnlineStateChanged(deviceId, isOnline = false))
    }

    /** Whether the device is connected to the service right now. */
    suspend fun isOnline(deviceId: Uuid): Boolean =
        connectionsMutex.withLock { connections.containsKey(deviceId) }

    /**
     * Every device that is connected right now. Read once for a caller describing a
     * whole list, so all of them are answered as of the same moment instead of each
     * asking on its own.
     */
    suspend fun onlineDevices(): Set<Uuid> = connectionsMutex.withLock { connections.keys.toSet() }

    // --- ping and ring ----------------------------------------------------

    /**
     * Asks the device to report back and waits [PING_TIMEOUT] for it. `null` means
     * it never answered — it may be offline, or asleep.
     */
    suspend fun ping(deviceId: Uuid, requestedByName: String, requestedBySource: PingSource): PingAck? {
        val ack = CompletableDeferred<PingAck>()
        pendingPingsMutex.withLock { pendingPings[deviceId] = ack }

        publish(DeviceEvent.PingRequested(deviceId, requestedByName, requestedBySource))

        return try {
            withTimeoutOrNull(PING_TIMEOUT) { ack.await() }
        } finally {
            pendingPingsMutex.withLock { pendingPings.remove(deviceId) }
        }
    }

    /** The device answering a ping. Ignored when nothing is waiting for it. */
    suspend fun acknowledgePing(deviceId: Uuid, hasDeliveredNotification: Boolean) {
        val ack = pendingPingsMutex.withLock { pendingPings[deviceId] } ?: return
        ack.complete(PingAck(hasDeliveredNotification))
    }

    /**
     * Asks the device to start ringing. Nothing is claimed about the outcome — a UI
     * only shows a ring the device itself confirmed (see [reportRingStarted]).
     */
    suspend fun requestRing(deviceId: Uuid, requestedByName: String) {
        ringRequestsMutex.withLock { ringRequests[deviceId] = requestedByName }
        publish(DeviceEvent.RingRequested(deviceId, requestedByName))
    }

    /** Asks the device to stop ringing. */
    suspend fun requestRingStop(deviceId: Uuid) {
        ringRequestsMutex.withLock { ringRequests.remove(deviceId) }
        publish(DeviceEvent.RingStopRequested(deviceId))
    }

    /**
     * The device confirming it is ringing. Named after whoever asked; a device that
     * started on its own is named after itself.
     */
    suspend fun reportRingStarted(deviceId: Uuid) {
        val requestedByName = ringRequestsMutex.withLock { ringRequests[deviceId] }
            ?: getById(deviceId)?.displayName
            ?: ""
        publish(DeviceEvent.RingStateChanged(deviceId, isRinging = true, requestedByName = requestedByName))
    }

    /** The device confirming it stopped. */
    suspend fun reportRingStopped(deviceId: Uuid) {
        val requestedByName = ringRequestsMutex.withLock { ringRequests.remove(deviceId) } ?: ""
        publish(DeviceEvent.RingStateChanged(deviceId, isRinging = false, requestedByName = requestedByName))
    }

    /**
     * Who asked the device to ring, or `null` when it is not ringing. Lets a UI that
     * connects mid-ring catch up, which the event stream alone cannot do.
     */
    suspend fun ringRequestedBy(deviceId: Uuid): String? =
        ringRequestsMutex.withLock { ringRequests[deviceId] }

    // --- optimization -----------------------------------------------------

    /**
     * How far the optimizer has got on a device. Announced to its owner rather than
     * on the device's stream: the one client that shows it follows every device of
     * the account at once.
     */
    suspend fun reportOptimizationProgress(deviceId: Uuid, ownerId: Uuid, progress: Double, isRunning: Boolean) {
        userRepository.publish(
            UserEvent.OptimizationProgressed(
                userId = ownerId,
                deviceId = deviceId,
                progress = progress,
                isRunning = isRunning,
            )
        )
    }

    // --- events -----------------------------------------------------------

    /**
     * The device's event stream. One flow per device, shared by every subscriber;
     * created on first use and kept for the lifetime of the process.
     */
    suspend fun events(deviceId: Uuid): SharedFlow<DeviceEvent> = flowFor(deviceId)

    /**
     * Publishes a device event. Called by this repository, and by the ones that
     * change a device's surroundings — never from outside [es.jvbabi.trails.data].
     */
    suspend fun publish(event: DeviceEvent) {
        flowFor(event.deviceId).emit(event)
    }

    private suspend fun flowFor(deviceId: Uuid): MutableSharedFlow<DeviceEvent> =
        eventsMutex.withLock { events.getOrPut(deviceId) { MutableSharedFlow(
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

    /** A change to the device itself is both its own event and a change to its owner's list. */
    private suspend fun announceChange(device: DeviceModel) {
        publish(DeviceEvent.Changed(device))
        userRepository.publish(UserEvent.DeviceChanged(userId = device.ownerId, device = device))
    }
}
