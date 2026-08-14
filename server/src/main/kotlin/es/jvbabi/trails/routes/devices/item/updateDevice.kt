package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.v1.devices.UpdateDeviceRequest
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.ifDefined
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `PATCH /devices/{deviceId}` — lets the owning user update their device (see
 * [UpdateDeviceRequest]). Reachable by both the app and the web realm. The
 * repository announces the change, so every open app and webapp socket shows the
 * new name without asking again.
 */
fun Route.updateDevice() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        patch {
            val actor = call.deviceActor()
                ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deviceId = call.parameters["deviceId"]?.let(Uuid::parseOrNull)
                ?: return@patch call.respond(HttpStatusCode.NotFound)

            val request = call.receive<UpdateDeviceRequest>()

            // Checked before anything is applied, and whether or not the request
            // asks for a change: a device that is not the caller's is answered like a
            // missing one, so a foreign device id cannot be probed for.
            deviceRepository.getOwnedById(deviceId, actor.userId)
                ?: return@patch call.respond(HttpStatusCode.Forbidden)

            request.customName.ifDefined { customName ->
                deviceRepository.setDisplayName(deviceId, actor.userId, customName)
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
