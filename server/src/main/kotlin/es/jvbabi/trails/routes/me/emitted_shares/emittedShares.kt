package es.jvbabi.trails.routes.me.emitted_shares

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.v1.me.EmittedShareResponse
import es.jvbabi.trails.data.ShareRepository
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.ktor.ext.inject

fun Route.getEmittedShares() {
    val shareRepository by inject<ShareRepository>()

    authenticate(TRAILS_USER_REALM) {
        get {
            val principal = call.principal<TrailsAppUserPrincipal>()!!
            principal.requireValidSession()

            val shares = shareRepository.listEmittedBy(principal.user.id).map { share ->
                EmittedShareResponse(
                    id = share.id,
                    deviceId = share.deviceId,
                    shareName = share.shareName,
                    locationHistorySeconds = share.locationHistorySeconds,
                    shareBatteryState = share.shareBatteryState,
                    allowMultiuse = share.allowMultiuse,
                    isLocked = share.isLocked,
                    createdAt = share.createdAt.epochSeconds,
                    redemptionCount = shareRepository.listRedemptionsOf(share.id).size.toLong(),
                )
            }

            call.respond(shares)
        }
    }
}
