package es.jvbabi.trails.routes.share.item

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.v1.share.UpdateShareRequest
import es.jvbabi.trails.api.v1.share.UpdateShareResponse
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.ShareUpdateResult
import es.jvbabi.trails.routes.devices.item.deviceActor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `PATCH /share/{shareId}` — lets the emitting user change a share's settings
 * (see [UpdateShareRequest]). Reachable by both the app and the web realm.
 *
 * The change applies to everyone who already redeemed the share: the settings live
 * on the share, and every redemption reads them per request. The repository
 * validates and writes in one go and announces the result, so open webapp sockets
 * pick it up and live share streams start honouring it at once.
 *
 * The outcome is an [UpdateShareResponse], so a rejected name arrives as its own
 * case rather than as a generic failure.
 */
fun Route.updateShare() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        patch {
            val actor = call.deviceActor()
                ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val shareId = call.parameters["shareId"]?.let(Uuid::parseOrNull)
                ?: return@patch call.respond(HttpStatusCode.NotFound)

            val request = call.receive<UpdateShareRequest>()

            val result = shareRepository.update(
                shareId = shareId,
                ownerId = actor.userId,
                shareName = request.shareName.getOrNull(),
                locationHistorySeconds = request.locationHistorySeconds.getOrNull(),
                shareBatteryState = request.shareBatteryState.getOrNull(),
            )

            call.respond<UpdateShareResponse>(
                message = when (result) {
                    is ShareUpdateResult.Updated -> UpdateShareResponse.ShareUpdated
                    ShareUpdateResult.NameTaken -> UpdateShareResponse.ShareNameAlreadyExists
                    ShareUpdateResult.NameEmpty -> UpdateShareResponse.ShareNameEmpty
                    ShareUpdateResult.NotAllowed -> UpdateShareResponse.NotAllowed
                },
                status = when (result) {
                    is ShareUpdateResult.Updated -> HttpStatusCode.OK
                    ShareUpdateResult.NameTaken -> HttpStatusCode.Conflict
                    ShareUpdateResult.NameEmpty -> HttpStatusCode.BadRequest
                    ShareUpdateResult.NotAllowed -> HttpStatusCode.Forbidden
                },
            )
        }
    }
}
