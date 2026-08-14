package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.DeviceRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `DELETE /devices/{deviceId}` — removes one of the caller's own devices.
 * Reachable by both the app and the web realm: an app deletion records the acting
 * device session, a browser deletion has none to record (and is surfaced as
 * "Browser" to other clients).
 *
 * The device is only ever soft-deleted, and the repository announces the removal —
 * to the device itself, so its own socket learns it is gone, and to the owner, so
 * their other sessions drop it from the list.
 */
fun Route.deleteDevice() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        delete {
            // Normalise both realms: the owning user is required, the device
            // session is only present for app deletions.
            val appPrincipal = call.principal<TrailsAppUserPrincipal>()
            val userId = appPrincipal?.user?.id
                ?: call.principal<TrailsWebappPrincipal>()?.user?.id
                ?: return@delete call.respond(HttpStatusCode.Forbidden)
            appPrincipal?.requireValidSession()

            val deviceId = call.parameters["deviceId"]?.let(Uuid::parseOrNull)
                ?: return@delete call.respond(HttpStatusCode.NotFound)

            // Null means the device is missing, not the caller's, or already gone —
            // all answered as Forbidden, so the endpoint doesn't reveal which.
            deviceRepository.delete(deviceId, userId, deletedBySessionId = appPrincipal?.session?.id)
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
