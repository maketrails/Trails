package es.jvbabi.trails.routes.share.item.redeem

import es.jvbabi.trails.api.v1.share.RedeemShareResponse
import es.jvbabi.trails.data.ShareRedeemResult
import es.jvbabi.trails.data.ShareRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `POST /share/{shareId}/redeem` — turns a share link into a redemption the caller
 * can watch the device with. Unauthenticated: holding the share id is the
 * permission.
 *
 * A single-use share locks itself as it is redeemed, in the same transaction, so
 * two callers arriving together cannot both get one.
 */
fun Route.redeemShare() {
    val shareRepository by inject<ShareRepository>()

    post {
        val shareId = call.parameters["shareId"]?.let(Uuid::parseOrNull)
            ?: return@post call.respond(HttpStatusCode.NotFound)

        when (val result = shareRepository.redeem(shareId)) {
            is ShareRedeemResult.Success -> call.respond<RedeemShareResponse>(
                message = RedeemShareResponse.Success(activeShareId = result.activeShare.id),
                status = HttpStatusCode.OK,
            )

            ShareRedeemResult.Locked -> call.respond<RedeemShareResponse>(
                message = RedeemShareResponse.ShareLocked,
                status = HttpStatusCode.Forbidden,
            )

            ShareRedeemResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }
}
