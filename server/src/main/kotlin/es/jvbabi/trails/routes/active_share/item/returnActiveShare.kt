package es.jvbabi.trails.routes.active_share.item

import es.jvbabi.trails.data.ShareRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `POST /active-shares/{activeShareId}/return` — gives back a redeemed share by
 * deleting the redemption. Capability-based and unauthenticated: holding the
 * active-share id (an unguessable UUID) is the permission, mirroring the public
 * redeem/snapshot endpoints. This is the origin-homeserver half of a return; the
 * account server separately drops its saved reference.
 *
 * Deleting the redemption deliberately does **not** unlock a spent single-use
 * share, so the link cannot be redeemed again.
 *
 * A `POST` (not `DELETE`) so a cross-homeserver browser call stays a CORS
 * "simple request" and needs no preflight; the origin is allowed application-wide
 * by `installCors`.
 */
fun Route.returnActiveShare() {
    val shareRepository by inject<ShareRepository>()

    post {
        val activeShareId = call.parameters["activeShareId"]?.let(Uuid::parseOrNull)
            ?: return@post call.respond(HttpStatusCode.NotFound)

        // Idempotent: an already-returned (missing) redemption is still a success.
        shareRepository.returnActiveShare(activeShareId)

        call.respond(HttpStatusCode.NoContent)
    }
}
