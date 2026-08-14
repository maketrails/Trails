package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.shared.dto.RingDeviceResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Starts ringing one of the caller's own devices. Generic REST endpoint (app or
 * web). The ring is only reflected in the UIs once the target device confirms it
 * (see the ring-state socket) — this endpoint merely asks for it.
 */
fun Route.ringDevice() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor()
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)
            val device = call.ownDevice(deviceRepository, actor.userId)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)

            deviceRepository.requestRing(device.id, requestedByName = actor.sourceName)

            call.respond<RingDeviceResponse>(RingDeviceResponse.Success(hasRingingStarted = true))
        }
    }
}

/**
 * Requests a ring previously started on one of the caller's own devices to stop.
 */
fun Route.stopRingDevice() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor()
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)
            val device = call.ownDevice(deviceRepository, actor.userId)
                ?: return@post call.respond<RingDeviceResponse>(RingDeviceResponse.Forbidden)

            deviceRepository.requestRingStop(device.id)

            call.respond<RingDeviceResponse>(RingDeviceResponse.Success(hasRingingStarted = false))
        }
    }
}
