package es.jvbabi.trails.routes.me

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.shared.dto.MeResponse
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.me() {
    authenticate(TRAILS_USER_REALM) {
        get {
            val auth = call.principal<TrailsAppUserPrincipal>()!!
            auth.requireValidSession()

            call.respond(
                MeResponse(
                    id = auth.user.id.toString(),
                    username = auth.user.username,
                    thisDeviceId = auth.device.id.toString(),
                )
            )
        }
    }
}
