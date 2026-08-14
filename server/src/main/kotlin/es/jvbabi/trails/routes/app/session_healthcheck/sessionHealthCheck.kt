package es.jvbabi.trails.routes.app.session_healthcheck

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.shared.dto.SessionHealthResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.sessionHealthCheck() {

    authenticate(TRAILS_USER_REALM, optional = true) {
        get {
            val principal = call.principal<TrailsAppUserPrincipal>()!!

            // The principal is resolved per request, so its device carries the
            // deletion as it stands right now.
            val deletion = principal.device.deletion
            if (deletion != null) {
                call.respond<SessionHealthResponse>(SessionHealthResponse.DeviceDeleted(
                    deletedByDeviceName = deletion.deletedByDeviceName ?: "Browser"
                ))
                return@get
            }

            call.respond<SessionHealthResponse>(SessionHealthResponse.Valid)
        }
    }
}
