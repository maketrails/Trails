package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.DeviceSubscriptionMessage
import es.jvbabi.trails.data.DeviceSubscriptionRepository
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.routes.app.deviceRingInfo
import es.jvbabi.trails.shared.dto.RingDeviceResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * Starts ringing one of the caller's own devices. Generic REST endpoint (app or
 * web). The ring is only reflected in the UIs once the target device explicitly
 * confirms it (see the ring-state socket) — this endpoint merely triggers it.
 */
fun Route.ringDevice() {
    val db by inject<DatabaseManager>()
    val deviceSubscriptionRepository by inject<DeviceSubscriptionRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor(db)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)
            val device = call.ownDevice(db, actor.userId)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)

            deviceRingInfo[device.id.value] = actor.sourceName
            deviceSubscriptionRepository.getFlowForDeviceSubscription(device.id.value)
                .emit(DeviceSubscriptionMessage.Ring(device, pingedByDeviceName = actor.sourceName))

            call.respond<RingDeviceResponse>(RingDeviceResponse.Success(hasRingingStarted = true))
        }
    }
}

/**
 * Requests a ring previously started on one of the caller's own devices to stop.
 */
fun Route.stopRingDevice() {
    val db by inject<DatabaseManager>()
    val deviceSubscriptionRepository by inject<DeviceSubscriptionRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor(db)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)
            val device = call.ownDevice(db, actor.userId)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)

            deviceRingInfo.remove(device.id.value)
            deviceSubscriptionRepository.getFlowForDeviceSubscription(device.id.value)
                .emit(DeviceSubscriptionMessage.RingStop(device))

            call.respond<RingDeviceResponse>(RingDeviceResponse.Success(hasRingingStarted = false))
        }
    }
}

/**
 * Resolves the `{deviceId}` path parameter to a device owned by [userId], or
 * `null` if the id is missing/invalid or the device is not owned by the user.
 */
