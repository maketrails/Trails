package es.jvbabi.trails.routes.me.shares

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.ShareRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `DELETE /me/shares/{shareId}?homeserver=<host>` — removes a saved share from
 * the caller's account (the account-side half of "returning" a share). The
 * share is identified by its active-share id plus origin homeserver, matching
 * how it was registered. Reachable by both the app and the web realm.
 *
 * This only drops the account's backup reference; deleting the redemption on the
 * origin homeserver — and thus *not* lifting the share's lock — is the client's
 * separate, direct call to that origin.
 */
fun Route.deleteUserShare() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        delete {
            val appPrincipal = call.principal<TrailsAppUserPrincipal>()
            appPrincipal?.requireValidSession()
            val userId = appPrincipal?.user?.id
                ?: call.principal<TrailsWebappPrincipal>()?.user?.id
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            val shareId = call.parameters["shareId"]?.let(Uuid::parseOrNull)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            // Absent homeserver means a same-server share, stored as "".
            val homeserver = call.request.queryParameters["homeserver"] ?: ""

            shareRepository.removeSaved(userId = userId, activeShareId = shareId, homeserver = homeserver)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
