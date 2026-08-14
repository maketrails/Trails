package es.jvbabi.trails.routes.me.shares

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.v1.me.RegisterUserShareRequest
import es.jvbabi.trails.api.v1.me.UserShareResponse
import es.jvbabi.trails.data.ShareRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject

fun Route.getUserShares() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM) {
        get {
            val principal = call.principal<TrailsAppUserPrincipal>()!!
            principal.requireValidSession()

            val shares = shareRepository.listSavedBy(principal.user.id).map {
                UserShareResponse(
                    shareId = it.activeShareId,
                    homeserver = it.homeserver,
                    createdAt = it.createdAt.epochSeconds,
                )
            }

            call.respond(shares)
        }
    }
}

fun Route.registerUserShare() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM) {
        post {
            val principal = call.principal<TrailsAppUserPrincipal>()!!
            principal.requireValidSession()

            val request = call.receive<RegisterUserShareRequest>()

            shareRepository.save(
                userId = principal.user.id,
                activeShareId = request.shareId,
                homeserver = request.homeserver,
            )

            call.respond(HttpStatusCode.OK)
        }
    }
}
