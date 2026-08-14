package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.shared.dto.PingDeviceResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Triggers a "find my device" ping on one of the caller's own devices and waits
 * for the device to acknowledge it. Generic REST endpoint usable by both the app
 * and the web (see [deviceActor]); the waiting itself lives in the repository, so
 * a ping is answered the same way no matter where it came from.
 */
fun Route.pingDevice() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor()
                ?: return@post call.respond<PingDeviceResponse>(PingDeviceResponse.Forbidden)
            val device = call.ownDevice(deviceRepository, actor.userId)
                ?: return@post call.respond<PingDeviceResponse>(PingDeviceResponse.Forbidden)

            val ack = deviceRepository.ping(
                deviceId = device.id,
                requestedByName = actor.sourceName,
                requestedBySource = actor.source,
            )

            call.respond<PingDeviceResponse>(
                if (ack != null) PingDeviceResponse.Success(ack.hasDeliveredNotification)
                else PingDeviceResponse.Timeout
            )
        }
    }
}
