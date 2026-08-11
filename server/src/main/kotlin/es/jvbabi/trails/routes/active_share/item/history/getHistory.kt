package es.jvbabi.trails.routes.active_share.item.history

import es.jvbabi.trails.api.v1.history.LocationHistoryResponse
import es.jvbabi.trails.data.deviceTrack
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.mapper.toHistoryPoint
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `GET /active-shares/{activeShareId}/history` — the location history a redeemed
 * share is allowed to see, oldest point first. Capability-based and
 * unauthenticated, exactly like the snapshot channel: holding the active-share id
 * (an unguessable UUID) *is* the permission. A foreign homeserver's browser client
 * fetches this directly; the CORS headers that allow it come from the
 * application-wide plugin (see `installCors`), never from here.
 *
 * The share — not the caller — decides how much is revealed. The window comes
 * from `Share.locationHistorySeconds`:
 * - `0` — the share carries no history at all, so the point list is empty.
 * - negative — an unbounded window (the app encodes `Duration.INFINITE` this way,
 *   as `Long.MAX_VALUE` seconds truncated to an `Int`), so everything is returned
 *   and `history_seconds` is reported as null.
 * - otherwise — only points recorded within that many seconds of now.
 *
 * `?since=<epoch millis>` lets a caller that already holds part of the history ask for
 * the rest: it is the `cursor` of an earlier response and filters on when a row was
 * **stored**, not on when its position was recorded. The two cut along different axes
 * and both apply, so this can only ever *narrow* what the share reveals — the
 * retention window above is untouched by it. `history_seconds` keeps reporting the
 * share's window either way, since it describes the share and not one request. An
 * unparseable value is ignored.
 *
 * The battery state is withheld unless the share opted in, mirroring the snapshot
 * endpoints.
 */
fun Route.getActiveShareHistory() {
    val db by inject<DatabaseManager>()

    get {
        val activeShareId = call.parameters["activeShareId"]?.let(Uuid::parseOrNull)
            ?: throw EntityNotFoundException("Active share not found")

        val requestedSince = call.request.queryParameters["since"]
            ?.toLongOrNull()
            ?.let(Instant::fromEpochMilliseconds)

        val response = db.transaction {
            // A returned share is deleted and a removed device is soft-deleted;
            // both are answered as a plain 404 so a spent capability cannot be
            // replayed to mine history.
            val activeShare = ActiveShare.findById(activeShareId)
                ?: throw EntityNotFoundException("Active share not found")
            val share = activeShare.share
            val device = share.device
            if (device.deletion != null) throw EntityNotFoundException("Active share not found")

            val historySeconds = share.locationHistorySeconds
            if (historySeconds == 0) {
                return@transaction LocationHistoryResponse(historySeconds = 0, points = emptyList())
            }

            val window = if (historySeconds < 0) null else Clock.System.now() - historySeconds.seconds
            val track = deviceTrack(device, notOlderThan = window, storedSince = requestedSince)

            LocationHistoryResponse(
                historySeconds = historySeconds.takeIf { it > 0 },
                // Null when nothing came back: there is no new cursor to report, and the
                // caller keeps the one it already has.
                cursor = track.maxOfOrNull { it.insertedAt.toEpochMilliseconds() },
                points = track.map { it.toHistoryPoint(includeBattery = share.shareBatteryState) },
            )
        }

        call.respond(response)
    }
}
