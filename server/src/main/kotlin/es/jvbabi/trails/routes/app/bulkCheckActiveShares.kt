package es.jvbabi.trails.routes.app

import es.jvbabi.trails.api.v1.active_shares.BulkCheckActiveSharesRequest
import es.jvbabi.trails.api.v1.active_shares.BulkCheckActiveSharesResponse
import es.jvbabi.trails.data.ShareRepository
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * `POST /app/active-shares/bulk-check` — given a list of active-share ids, returns
 * the subset that still exist here (present and their device not deleted).
 *
 * App-specific: at start the app reconciles its saved shares by asking each origin
 * homeserver once for all of that host's shares, then drops the ones missing here.
 * Capability-based and unauthenticated (the ids are the capability), so it also
 * works against foreign homeservers where the app has no session — hence a public
 * sibling of the app socket rather than something behind the app auth realm.
 */
fun Route.bulkCheckActiveShares() {
    val shareRepository by inject<ShareRepository>()

    post {
        val request = call.receive<BulkCheckActiveSharesRequest>()
        val existing = shareRepository.filterExistingActiveShares(request.activeShareIds)
        call.respond(BulkCheckActiveSharesResponse(existingActiveShareIds = existing))
    }
}
