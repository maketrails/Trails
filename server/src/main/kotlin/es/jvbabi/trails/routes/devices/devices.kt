package es.jvbabi.trails.routes.devices

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.shared.dto.DeviceResponse
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.devices() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM) {
        get {
            val auth = call.principal<TrailsAppUserPrincipal>()!!
            auth.requireValidSession()

            val devices = deviceRepository.listOwnedBy(auth.user.id).map { device ->
                DeviceResponse(
                    id = device.id.toString(),
                    manufacturer = device.manufacturer,
                    model = device.model,
                    friendlyName = device.friendlyName,
                    displayName = device.displayName,
                    ownerId = device.ownerId.toString(),
                )
            }

            call.respond(devices)
        }
    }
}
