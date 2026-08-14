package es.jvbabi.trails.routes.app

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.SnapshotWriteResult
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.event.ActiveShareEvent
import es.jvbabi.trails.data.event.DeviceEvent
import es.jvbabi.trails.data.event.UserEvent
import es.jvbabi.trails.data.model.ShareModel
import es.jvbabi.trails.data.model.SnapshotModel
import es.jvbabi.trails.data.model.forShare
import es.jvbabi.trails.shared.dto.DeviceResponse
import es.jvbabi.trails.shared.dto.websocket.PingSource
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketAppMessage
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketServerMessage
import io.ktor.serialization.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private typealias ActiveShareId = Uuid
private typealias DeviceId = Uuid

/**
 * The app's one connection: it uploads what its device recorded and receives
 * everything the app shows — its own devices, the shares it holds, and the requests
 * aimed at it (ping, ring).
 *
 * Every subscription here is a repository event stream, and which stream is
 * subscribed to *is* the authorization: an own device's stream is only reachable
 * with a session that owns it, and a redemption is served by
 * [ShareRepository.activeShareEvents], which has already applied what the share
 * reveals. Nothing in this file decides what a caller may see.
 */
fun Route.app() {

    val deviceRepository by inject<DeviceRepository>()
    val trackRepository by inject<TrackRepository>()
    val shareRepository by inject<ShareRepository>()
    val userRepository by inject<UserRepository>()

    authenticate(TRAILS_USER_REALM, optional = true) {
        webSocket("/ws") {
            val principal = call.principal<TrailsAppUserPrincipal>()
            principal?.requireValidSession()

            val appSocketLogger = KtorSimpleLogger("AppWebSocket")

            val subscribedShares = mutableSetOf<ActiveShareId>()
            val shareSubscriptionRtUpdaters = mutableMapOf<ActiveShareId, Job>()
            val ownDeviceSubscriptionRtUpdaters = mutableMapOf<DeviceId, Job>()

            val emitRtUpdates = MutableStateFlow(true)

            /**
             * Sends where the server last saw a device, right as the client subscribes.
             *
             * Without this a client only learns a position once the device reports one,
             * so a device that is offline — the very case where its whereabouts matter —
             * leaves the app showing whatever it happened to know from last time instead
             * of what the server knows.
             *
             * What a share may reveal stays the share's decision, on the same window its
             * history uses: nothing at all at `0`, everything at an unbounded one,
             * otherwise only a position recorded inside the window. An own device carries
             * no such limit.
             *
             * Sent before the live subscription starts, so a position that arrives while
             * this is on its way is the one the client keeps. A client that has asked not
             * to be sent positions at all is left alone, on the same footing as the live
             * updates below.
             */
            suspend fun sendLastKnownPosition(deviceId: DeviceId, share: ShareModel?, activeShareId: ActiveShareId?) {
                if (!emitRtUpdates.value) return
                // A removed device gives nothing away, exactly as its snapshot and history
                // endpoints answer a plain 404. The subscription reports the removal itself.
                val device = deviceRepository.getById(deviceId) ?: return
                if (device.isDeleted) return

                if (share != null && !share.revealsHistory) return
                val notOlderThan = share?.historySeconds?.let { Clock.System.now() - it.seconds }

                val snapshot = trackRepository.latestSnapshot(deviceId, notOlderThan) ?: return
                sendSerialized<TrailsWebSocketServerMessage>(
                    snapshotMessage(
                        snapshot = if (share != null) snapshot.forShare(share) else snapshot,
                        target = if (activeShareId != null) {
                            TrailsWebSocketServerMessage.Snapshot.Target.Share(activeShareId.toString())
                        } else {
                            TrailsWebSocketServerMessage.Snapshot.Target.Device(deviceId.toString())
                        },
                    )
                )
            }

            /** The same for everything this connection is subscribed to. */
            suspend fun sendLastKnownPositions() {
                ownDeviceSubscriptionRtUpdaters.keys.toList().forEach { deviceId ->
                    sendLastKnownPosition(deviceId, share = null, activeShareId = null)
                }

                shareSubscriptionRtUpdaters.keys.toList().forEach { activeShareId ->
                    val shared = shareRepository.getSharedDevice(activeShareId) ?: return@forEach
                    sendLastKnownPosition(shared.device.id, shared.share, activeShareId)
                }
            }

            suspend fun startShareSubscription(activeShareId: ActiveShareId) {
                if (shareSubscriptionRtUpdaters[activeShareId]?.isActive == true) return
                val shared = shareRepository.getSharedDevice(activeShareId) ?: return

                sendLastKnownPosition(shared.device.id, shared.share, activeShareId)

                shareSubscriptionRtUpdaters[activeShareId] = launch {
                    // The share's own stream, already filtered down to what this
                    // redemption reveals. It ends of its own accord once the share is
                    // gone — one share ending is not a reason to drop the connection,
                    // which serves the app's own devices too.
                    shareRepository.activeShareEvents(activeShareId)
                        .mapNotNull { it.toAppMessage(activeShareId) }
                        .filterNot { it is TrailsWebSocketServerMessage.Snapshot && !emitRtUpdates.value }
                        .onEach { message -> sendSerialized<TrailsWebSocketServerMessage>(message) }
                        .collect()
                }.also {
                    it.invokeOnCompletion { shareSubscriptionRtUpdaters.remove(activeShareId) }
                }
            }

            suspend fun startOwnDeviceSubscription(deviceId: DeviceId) {
                requireNotNull(principal) { "Cannot subscribe to own device without a principal" }
                if (ownDeviceSubscriptionRtUpdaters[deviceId]?.isActive == true) return
                if (deviceRepository.getOwnedById(deviceId, principal.user.id) == null) return

                sendLastKnownPosition(deviceId, share = null, activeShareId = null)

                ownDeviceSubscriptionRtUpdaters[deviceId] = launch {
                    deviceRepository.events(deviceId)
                        .mapNotNull { it.toAppMessage(thisDeviceId = principal.device.id) }
                        .filterNot { it.message is TrailsWebSocketServerMessage.Snapshot && !emitRtUpdates.value }
                        .onEach { message -> sendSerialized<TrailsWebSocketServerMessage>(message.message) }
                        .takeWhile { !it.closeConnectionAfterSending }
                        .collect()
                    // Reached when this session's own device was removed: there is
                    // nothing left for the connection to serve.
                    this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                }
            }

            if (principal != null) {
                deviceRepository.listOwnedBy(principal.user.id).forEach { startOwnDeviceSubscription(it.id) }
            }

            launch(CoroutineName("SharesRtUpdates")) {
                subscribedShares.forEach { startShareSubscription(it) }
            }

            launch(CoroutineName("ThisUserEvents")) {
                if (principal == null) return@launch
                userRepository.events(principal.user.id)
                    .onEach { event ->
                        when (event) {
                            // A device that was added or came back keeps its own
                            // subscription; one that is gone drops it.
                            is UserEvent.DeviceChanged -> startOwnDeviceSubscription(event.device.id)
                            is UserEvent.DeviceRemoved ->
                                ownDeviceSubscriptionRtUpdaters.remove(event.deletion.deviceId)?.cancel()
                            is UserEvent.SavedSharesChanged -> {}
                            is UserEvent.EmittedSharesChanged -> {}
                            is UserEvent.OptimizationProgressed -> {}
                        }
                    }
                    .mapNotNull { it.toAppMessage() }
                    .onEach { this@webSocket.sendSerialized(it) }
                    .takeWhile { this@webSocket.isActive }
                    .collect()
            }

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val message = converter!!.deserialize<TrailsWebSocketAppMessage>(frame)
                    try {
                        when (message) {
                            is TrailsWebSocketAppMessage.DataSnapshot -> {
                                if (principal == null) continue
                                launch {
                                    // Storing is idempotent, so a re-upload is acknowledged
                                    // rather than written twice. Only a write that genuinely
                                    // failed goes unacknowledged, which is what makes the
                                    // device try again.
                                    val result = trackRepository.addSnapshot(
                                        deviceId = principal.device.id,
                                        snapshotId = message.snapshotId,
                                        recordedAt = Instant.fromEpochSeconds(message.time),
                                        latitude = message.latitude,
                                        longitude = message.longitude,
                                        locationAccuracy = message.locationAccuracy.toDouble(),
                                        bearing = message.bearing.toDouble(),
                                        bearingAccuracy = message.bearingAccuracy?.toDouble(),
                                        batteryLevel = message.batteryLevel,
                                        batteryCharging = message.batteryCharging,
                                    )

                                    if (result is SnapshotWriteResult.Failed) {
                                        appSocketLogger.warn(
                                            "Could not store snapshot ${message.snapshotId}",
                                            result.error,
                                        )
                                        return@launch
                                    }

                                    sendSerialized<TrailsWebSocketServerMessage>(
                                        TrailsWebSocketServerMessage.SnapshotAcknowledged(
                                            snapshotId = message.snapshotId,
                                        )
                                    )
                                }
                            }

                            is TrailsWebSocketAppMessage.ShareSubscribe -> {
                                message.shareIds
                                    .map { id -> Uuid.parse(id) }
                                    .forEach { id ->
                                        subscribedShares.add(id)
                                        startShareSubscription(id)
                                    }
                            }

                            is TrailsWebSocketAppMessage.ShareUnsubscribe -> {
                                val unsubscribeIds = message.shareIds.map { Uuid.parse(it) }
                                shareSubscriptionRtUpdaters.filterKeys { it in unsubscribeIds }
                                    .forEach { it.value.cancel() }
                            }

                            is TrailsWebSocketAppMessage.StartRtUpdates -> {
                                emitRtUpdates.value = true
                                // The other moment the client asks for positions, and the
                                // one the user actually notices: the app coming back to
                                // the foreground. Its subscriptions outlive that, so
                                // nothing else would tell it what stood still while it was
                                // away.
                                launch { sendLastKnownPositions() }
                            }
                            is TrailsWebSocketAppMessage.StopRtUpdates -> emitRtUpdates.value = false

                            is TrailsWebSocketAppMessage.Pong -> {
                                if (principal == null) continue
                                deviceRepository.acknowledgePing(
                                    deviceId = principal.device.id,
                                    hasDeliveredNotification = message.hasDeliveredNotification,
                                )
                            }

                            is TrailsWebSocketAppMessage.RingStart -> {
                                if (principal == null) continue
                                deviceRepository.reportRingStarted(principal.device.id)
                            }

                            is TrailsWebSocketAppMessage.RingStop -> {
                                if (principal == null) continue
                                deviceRepository.reportRingStopped(principal.device.id)
                            }

                            is TrailsWebSocketAppMessage.DevicePing -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                if (deviceRepository.getOwnedById(targetDeviceId, principal.user.id) == null) {
                                    sendSerialized(
                                        TrailsWebSocketServerMessage.PingResult(
                                            deviceId = message.deviceId,
                                            success = false,
                                            errorMessage = "Not allowed",
                                        )
                                    )
                                    continue
                                }

                                // Waiting for the answer must not stop this connection from
                                // reading, so the ping runs beside the receive loop.
                                launch {
                                    val ack = deviceRepository.ping(
                                        deviceId = targetDeviceId,
                                        requestedByName = principal.device.displayName,
                                        requestedBySource = PingSource.DEVICE,
                                    )
                                    if (ack != null) {
                                        sendSerialized(
                                            TrailsWebSocketServerMessage.PingResult(
                                                deviceId = message.deviceId,
                                                success = true,
                                                hasDeliveredNotification = ack.hasDeliveredNotification,
                                            )
                                        )
                                    } else {
                                        sendSerialized(
                                            TrailsWebSocketServerMessage.PingResult(
                                                deviceId = message.deviceId,
                                                success = false,
                                                errorMessage = "Timeout",
                                            )
                                        )
                                    }
                                }
                            }

                            is TrailsWebSocketAppMessage.DeviceRing -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                if (deviceRepository.getOwnedById(targetDeviceId, principal.user.id) == null) continue
                                deviceRepository.requestRing(
                                    deviceId = targetDeviceId,
                                    requestedByName = principal.device.displayName,
                                )
                            }

                            is TrailsWebSocketAppMessage.DeviceRingStop -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                if (deviceRepository.getById(targetDeviceId) == null) continue
                                deviceRepository.requestRingStop(targetDeviceId)
                            }
                        }
                    } catch (e: Exception) {
                        appSocketLogger.error("""WebSocket message could not be handled:
                            |Message: $message
                            |Error: ${e.stackTraceToString()}
                        """.trimMargin())
                    }
                }
            }
        }
    }
}

/** The wire shape of one stored position. */
private fun snapshotMessage(
    snapshot: SnapshotModel,
    target: TrailsWebSocketServerMessage.Snapshot.Target,
): TrailsWebSocketServerMessage.Snapshot = TrailsWebSocketServerMessage.Snapshot(
    snapshotId = snapshot.id,
    target = target,
    timestamp = snapshot.createdAt.epochSeconds,
    location = TrailsWebSocketServerMessage.Snapshot.Location(
        latitude = snapshot.latitude,
        longitude = snapshot.longitude,
        bearing = snapshot.bearing.toFloat(),
        bearingAccuracy = snapshot.bearingAccuracy?.toFloat(),
        locationAccuracy = snapshot.locationAccuracy.toFloat(),
    ),
    batteryState = snapshot.battery?.let {
        TrailsWebSocketServerMessage.Snapshot.BatteryState(
            percentage = it.percentage,
            isCharging = it.isCharging,
        )
    },
)

/**
 * What one of the user's own devices sends to this connection.
 *
 * [thisDeviceId] is the device on the other end: a ping or a ring is a request
 * aimed at one device, so only its own connection acts on it — the owner's other
 * devices have no business ringing on its behalf.
 */
private fun DeviceEvent.toAppMessage(thisDeviceId: Uuid): AppSocketMessage? = when (this) {
    is DeviceEvent.SnapshotAdded -> AppSocketMessage(
        snapshotMessage(
            snapshot = snapshot,
            target = TrailsWebSocketServerMessage.Snapshot.Target.Device(deviceId.toString()),
        )
    )

    is DeviceEvent.Deleted -> AppSocketMessage(
        TrailsWebSocketServerMessage.DeviceDeleted(
            deletedByDeviceName = deletion.deletedByDeviceName ?: "Browser",
            deviceId = deletion.deviceId.toString(),
        ),
        closeConnectionAfterSending = deletion.deviceId == thisDeviceId,
    )

    is DeviceEvent.PingRequested -> if (deviceId != thisDeviceId) null else AppSocketMessage(
        TrailsWebSocketServerMessage.Ping(
            pingedByDeviceName = requestedByName,
            pingedBySource = requestedBySource,
        )
    )

    is DeviceEvent.RingRequested -> if (deviceId != thisDeviceId) null else AppSocketMessage(
        TrailsWebSocketServerMessage.Ring(ringedByDeviceName = requestedByName)
    )

    is DeviceEvent.RingStopRequested -> if (deviceId != thisDeviceId) null else AppSocketMessage(
        TrailsWebSocketServerMessage.RingStop
    )

    // The confirmed ring state is what the *other* UIs follow; this connection is
    // where it came from.
    is DeviceEvent.RingStateChanged -> null

    // A rename reaches the app through the account's stream, which carries the whole
    // device.
    is DeviceEvent.Changed -> null
}

/** What one held redemption sends to this connection. */
private fun ActiveShareEvent.toAppMessage(activeShareId: Uuid): TrailsWebSocketServerMessage? = when (this) {
    is ActiveShareEvent.SnapshotAdded -> snapshotMessage(
        snapshot = snapshot,
        target = TrailsWebSocketServerMessage.Snapshot.Target.Share(activeShareId.toString()),
    )

    is ActiveShareEvent.Gone -> TrailsWebSocketServerMessage.ShareDeleted(
        wasDeviceRemoved = wasDeviceRemoved,
        shareId = activeShareId.toString(),
    )

    // The app is told what a share reveals, not how it is configured; the next
    // position already reflects the change.
    is ActiveShareEvent.SettingsChanged -> null
}

/** What the account itself sends to this connection. */
private fun UserEvent.toAppMessage(): TrailsWebSocketServerMessage? = when (this) {
    is UserEvent.DeviceChanged -> TrailsWebSocketServerMessage.DeviceUpdated(
        data = DeviceResponse(
            id = device.id.toString(),
            manufacturer = device.manufacturer,
            model = device.model,
            friendlyName = device.friendlyName,
            displayName = device.displayName,
            ownerId = device.ownerId.toString(),
        )
    )

    is UserEvent.DeviceRemoved -> TrailsWebSocketServerMessage.DeviceDeleted(
        deletedByDeviceName = deletion.deletedByDeviceName ?: "Browser",
        deviceId = deletion.deviceId.toString(),
    )

    is UserEvent.SavedSharesChanged -> TrailsWebSocketServerMessage.ShareDeleted(
        wasDeviceRemoved = false,
        shareId = activeShareId.toString(),
    )

    // Emitted shares are not part of the app's socket state — it reads them via
    // `GET /me/emitted-shares` when it needs them.
    is UserEvent.EmittedSharesChanged -> null

    // The app draws no tracks, so optimization progress means nothing to it.
    is UserEvent.OptimizationProgressed -> null
}

data class AppSocketMessage(
    val message: TrailsWebSocketServerMessage,
    val closeConnectionAfterSending: Boolean = false,
)
