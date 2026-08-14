package es.jvbabi.trails.routes.share

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.v1.share.CreateShareRequest
import es.jvbabi.trails.api.v1.share.CreateShareResponse
import es.jvbabi.trails.data.ShareRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject

/**
 * `POST /share` — emits a new share for the device that is asking. A share is
 * always created for the calling device, so there is nothing to authorize beyond
 * the session itself.
 */
fun Route.createShare() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM) {
        post {
            val principal = call.principal<TrailsAppUserPrincipal>()!!
            principal.requireValidSession()

            val request = call.receive<CreateShareRequest>()

            // The name has to be free among *this user's* shares — it is how they
            // tell them apart.
            if (shareRepository.existsWithName(principal.user.id, request.shareName)) {
                call.respond<CreateShareResponse>(
                    message = CreateShareResponse.ShareNameAlreadyExists,
                    status = HttpStatusCode.Conflict
                )
                return@post
            }

            val share = shareRepository.create(
                deviceId = principal.device.id,
                shareName = request.shareName,
                locationHistorySeconds = request.locationHistorySeconds,
                allowMultiuse = request.allowMultiuse,
                shareBatteryState = request.shareBattery,
            ) ?: return@post call.respond(HttpStatusCode.Forbidden)

            call.respond<CreateShareResponse>(
                message = CreateShareResponse.ShareCreated(share.id),
                status = HttpStatusCode.Created
            )
        }
    }
}
