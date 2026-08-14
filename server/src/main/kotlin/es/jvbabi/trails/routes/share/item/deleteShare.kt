package es.jvbabi.trails.routes.share.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.routes.devices.item.deviceActor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `DELETE /share/{shareId}` — deletes a share the caller emitted. Reachable by
 * both the app and the web realm.
 *
 * Unlike a device, a share is deleted for real: its redemptions go with it, so
 * every link handed out stops working and nobody can see the device's location
 * through it anymore. Saved references to those redemptions that live on this
 * server are dropped too and their owners told — a reference on a foreign
 * homeserver cannot be touched from here, and those clients reconcile against this
 * server the usual way (a snapshot socket that finds nothing, or
 * `active-shares/bulk-check`).
 */
fun Route.deleteShare() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        delete {
            val actor = call.deviceActor()
                ?: return@delete call.respond(HttpStatusCode.Forbidden)
            val shareId = call.parameters["shareId"]?.let(Uuid::parseOrNull)
                ?: return@delete call.respond(HttpStatusCode.NotFound)

            // False means the share is missing or not the caller's, both answered as
            // Forbidden so the endpoint doesn't reveal which.
            val deleted = shareRepository.delete(shareId, actor.userId)
            if (!deleted) return@delete call.respond(HttpStatusCode.Forbidden)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
