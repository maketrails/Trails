package es.jvbabi.trails.routes.app

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.data.DeviceSubscriptionMessage
import es.jvbabi.trails.data.DeviceSubscriptionRepository
import es.jvbabi.trails.data.UserSubscriptionMessage
import es.jvbabi.trails.data.UserSubscriptionRepository
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.routes.devices.PingResult
import es.jvbabi.trails.routes.devices.pendingPings
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
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant
import kotlin.uuid.Uuid

private typealias ActiveShareId = Uuid
private typealias DeviceId = Uuid

val deviceRingInfo = mutableMapOf<Uuid, String>()

fun Route.app() {

    val db by inject<DatabaseManager>()
    val deviceSubscriptionRepository by inject<DeviceSubscriptionRepository>()
    val userSubscriptionRepository by inject<UserSubscriptionRepository>()

    authenticate(TRAILS_USER_REALM, optional = true) {
        webSocket("/ws") {
            val principal = call.principal<TrailsAppUserPrincipal>()
            principal?.requireValidSession()

            val appSocketLogger = KtorSimpleLogger("AppWebSocket")

            val subscribedShares = mutableSetOf<ActiveShareId>()
            val shareSubscriptionRtUpdaters = mutableMapOf<ActiveShareId, Job>()
            val ownDeviceSubscriptionRtUpdaters = mutableMapOf<DeviceId, Job>()

            val selfFlow =
                if (principal != null) deviceSubscriptionRepository.getFlowForDeviceSubscription(db.transaction { principal.device.id.value }) else null

            val emitRtUpdates = MutableStateFlow(true)

            suspend fun startShareSubscription(shareId: ActiveShareId) {
                if (shareSubscriptionRtUpdaters[shareId]?.isActive == true) return
                val share = db.transaction { ActiveShare.findById(shareId) } ?: return
                val device = db.transaction { share.share.device }

                shareSubscriptionRtUpdaters[shareId] = launch {
                    deviceSubscriptionRepository.getFlowForDeviceSubscription(device.id.value)
                        .mapNotNull { it.toAppSocketMessage(null, share) }
                        .filterNot { it.message is TrailsWebSocketServerMessage.Snapshot && !emitRtUpdates.value }
                        .onEach { message ->
                            sendSerialized<TrailsWebSocketServerMessage>(message.message)
                        }
                        .takeWhile { !it.closeConnectionAfterSending }
                        .collect()
                    this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                }.also {
                    it.invokeOnCompletion { shareSubscriptionRtUpdaters.remove(shareId) }
                }
            }

            suspend fun startOwnDeviceSubscription(deviceId: DeviceId) {
                requireNotNull(principal) { "Cannot subscribe to own device without a principal" }

                val device = db.transaction { Device.findById(deviceId) } ?: return

                if (ownDeviceSubscriptionRtUpdaters[device.id.value]?.isActive == true) return

                ownDeviceSubscriptionRtUpdaters[device.id.value] = launch {
                    deviceSubscriptionRepository.getFlowForDeviceSubscription(device.id.value)
                        .mapNotNull { it.toAppSocketMessage(principal, null) }
                        .filterNot { it.message is TrailsWebSocketServerMessage.Snapshot && !emitRtUpdates.value }
                        .onEach { message ->
                            sendSerialized<TrailsWebSocketServerMessage>(message.message)
                        }
                        .takeWhile { !it.closeConnectionAfterSending }
                        .collect()
                    this@webSocket.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                }
            }

            if (principal != null) {
                val ownDevices = db.transaction { principal.user.devices.toList().filter { it.deletion == null } }
                ownDevices.forEach { startOwnDeviceSubscription(it.id.value) }
            }

            launch(CoroutineName("SharesRtUpdates")) {
                subscribedShares.forEach { startShareSubscription(it) }
            }

            launch(CoroutineName("ThisUserEvents")) {
                if (principal == null) return@launch
                userSubscriptionRepository.getFlowForUser(principal.user.id.value)
                    .onEach { message ->
                        when (message) {
                            is UserSubscriptionMessage.DeviceUpdated -> {
                                startOwnDeviceSubscription(message.device.id.value)
                            }
                            is UserSubscriptionMessage.DeviceDeleted -> {
                                val deviceId = db.transaction { message.deletion.device.id.value }
                                ownDeviceSubscriptionRtUpdaters[deviceId]?.cancel()
                                ownDeviceSubscriptionRtUpdaters.remove(deviceId)
                            }
                            is UserSubscriptionMessage.RingState -> { }
                            is UserSubscriptionMessage.SharesChanged -> { }
                            is UserSubscriptionMessage.EmittedSharesChanged -> { }
                        }
                    }
                    .mapNotNull { it.toAppSocketMessage(principal) }
                    .onEach { this@webSocket.sendSerialized(it.message) }
                    .takeWhile { !it.closeConnectionAfterSending && this@webSocket.isActive }
                    .collect()
            }

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val message = converter!!.deserialize<TrailsWebSocketAppMessage>(frame)
                    try {
                        println(message)
                        when (message) {
                            is TrailsWebSocketAppMessage.DataSnapshot -> {
                                if (principal == null) continue
                                launch {
                                    val createdAt = Instant.fromEpochSeconds(message.time)

                                    // A snapshot counts as stored once its ID is known, or once the
                                    // device has one for that second — the unique
                                    // (device, timestamp) index allows only one. Must run inside a
                                    // transaction.
                                    val isAlreadyStored = {
                                        DataSnapshot.findById(message.snapshotId) != null ||
                                                !DataSnapshot.find {
                                                    (DataSnapshots.device eq principal.device.id) and
                                                            (DataSnapshots.createdAt eq createdAt) and
                                                            (DataSnapshots.isRaw eq true)
                                                }.empty()
                                    }

                                    // Storing has to be idempotent: an app whose acknowledgement
                                    // got lost re-uploads the snapshot, and a snapshot recorded in
                                    // the same second as an existing one collides with the index.
                                    // Both cases mean the data is already stored, so they are
                                    // acknowledged instead of failing the write — otherwise the
                                    // app retries them forever.
                                    val stored = runCatching {
                                        db.transaction {
                                            if (isAlreadyStored()) return@transaction null

                                            DataSnapshot.new(message.snapshotId) {
                                                this.device = principal.device
                                                this.latitude = message.latitude
                                                this.longitude = message.longitude
                                                this.bearing = message.bearing.toDouble()
                                                this.bearingAccuracy = message.bearingAccuracy?.toDouble()
                                                this.locationAccuracy = message.locationAccuracy.toDouble()
                                                this.batteryLevel = message.batteryLevel
                                                this.batteryCharging = message.batteryCharging
                                                this.createdAt = createdAt
                                                this.isRaw = true
                                            }
                                        }
                                    }.getOrElse { error ->
                                        // Two uploads for the same second can race past the check
                                        // above. Only acknowledge if the data did land.
                                        val landed = runCatching { db.transaction(isAlreadyStored) }
                                            .getOrDefault(false)

                                        if (!landed) {
                                            appSocketLogger.warn("Could not store snapshot ${message.snapshotId}", error)
                                            return@launch
                                        }
                                        null
                                    }

                                    if (stored != null && selfFlow != null && selfFlow.subscriptionCount.value > 0) {
                                        selfFlow.emit(DeviceSubscriptionMessage.Snapshot(stored))
                                    }

                                    sendSerialized<TrailsWebSocketServerMessage>(TrailsWebSocketServerMessage.SnapshotAcknowledged(
                                        snapshotId = message.snapshotId,
                                    ))
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
                                shareSubscriptionRtUpdaters.filterKeys { it in unsubscribeIds }.forEach { it.value.cancel() }
                            }

                            is TrailsWebSocketAppMessage.StartRtUpdates -> emitRtUpdates.value = true
                            is TrailsWebSocketAppMessage.StopRtUpdates -> emitRtUpdates.value = false

                            is TrailsWebSocketAppMessage.Pong -> {
                                if (principal == null) continue
                                val deferred = pendingPings[principal.device.id.value] ?: continue
                                deferred.complete(PingResult(message.hasDeliveredNotification))
                            }

                            is TrailsWebSocketAppMessage.RingStart -> {
                                if (principal == null) continue
                                val deviceId = principal.device.id.value
                                val ringedBy = deviceRingInfo[deviceId] ?: principal.device.displayName
                                val userFlow = userSubscriptionRepository.getFlowForUser(principal.user.id.value)
                                userFlow.emit(UserSubscriptionMessage.RingState(
                                    deviceId = deviceId,
                                    isRinging = true,
                                    ringedByDeviceName = ringedBy,
                                ))
                            }

                            is TrailsWebSocketAppMessage.RingStop -> {
                                if (principal == null) continue
                                val deviceId = principal.device.id.value
                                val ringedBy = deviceRingInfo.remove(deviceId) ?: ""
                                val userFlow = userSubscriptionRepository.getFlowForUser(principal.user.id.value)
                                userFlow.emit(UserSubscriptionMessage.RingState(
                                    deviceId = deviceId,
                                    isRinging = false,
                                    ringedByDeviceName = ringedBy,
                                ))
                            }

                            is TrailsWebSocketAppMessage.DevicePing -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                val targetDevice = db.transaction { Device.findById(targetDeviceId) }
                                if (targetDevice == null || db.transaction { targetDevice.owner.id.value != principal.user.id.value }) {
                                    sendSerialized(TrailsWebSocketServerMessage.PingResult(deviceId = message.deviceId, success = false, errorMessage = "Not allowed"))
                                    continue
                                }
                                val deferred = CompletableDeferred<PingResult>()
                                pendingPings[targetDeviceId] = deferred
                                val deviceFlow = deviceSubscriptionRepository.getFlowForDeviceSubscription(targetDeviceId)
                                deviceFlow.emit(DeviceSubscriptionMessage.Ping(targetDevice, pingedByDeviceName = principal.device.displayName))
                                launch {
                                    val result = withTimeoutOrNull(5.seconds) { deferred.await() }
                                    pendingPings.remove(targetDeviceId)
                                    if (result != null) {
                                        sendSerialized(TrailsWebSocketServerMessage.PingResult(
                                            deviceId = message.deviceId,
                                            success = true,
                                            hasDeliveredNotification = result.hasDeliveredNotification
                                        ))
                                    } else {
                                        sendSerialized(TrailsWebSocketServerMessage.PingResult(deviceId = message.deviceId, success = false, errorMessage = "Timeout"))
                                    }
                                }
                            }

                            is TrailsWebSocketAppMessage.DeviceRing -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                val targetDevice = db.transaction { Device.findById(targetDeviceId) }
                                if (targetDevice == null || db.transaction { targetDevice.owner.id.value != principal.user.id.value }) continue
                                deviceRingInfo[targetDeviceId] = principal.device.displayName
                                val deviceFlow = deviceSubscriptionRepository.getFlowForDeviceSubscription(targetDeviceId)
                                deviceFlow.emit(DeviceSubscriptionMessage.Ring(targetDevice, pingedByDeviceName = principal.device.displayName))
                            }

                            is TrailsWebSocketAppMessage.DeviceRingStop -> {
                                if (principal == null) continue
                                val targetDeviceId = Uuid.parse(message.deviceId)
                                val targetDevice = db.transaction { Device.findById(targetDeviceId) }
                                if (targetDevice == null) continue
                                val deviceFlow = deviceSubscriptionRepository.getFlowForDeviceSubscription(targetDeviceId)
                                deviceFlow.emit(DeviceSubscriptionMessage.RingStop(targetDevice))
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

private const val MIN_DISTANCE_METERS = 10.0
private const val EARTH_RADIUS_METERS = 6371000.0

private fun distanceMeters(
    latitude1: Double,
    longitude1: Double,
    latitude2: Double,
    longitude2: Double,
): Double {
    val lat1 = Math.toRadians(latitude1)
    val lat2 = Math.toRadians(latitude2)
    val deltaLat = Math.toRadians(latitude2 - latitude1)
    val deltaLon = Math.toRadians(longitude2 - longitude1)

    val a = sin(deltaLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2).let { it * it }
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}



data class AppSocketMessage(
    val message: TrailsWebSocketServerMessage,
    val closeConnectionAfterSending: Boolean = false,
)