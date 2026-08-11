package es.jvbabi.trails.routes.devices.item.history

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.v1.history.LocationHistoryResponse
import es.jvbabi.trails.data.TrackSource
import es.jvbabi.trails.data.deviceTrack
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.mapper.toHistoryPoint
import es.jvbabi.trails.routes.devices.item.deviceActor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * `GET /devices/{deviceId}/history` — the complete recorded location history of
 * one of the caller's **own** devices, oldest point first. Reachable by both the
 * app and the web realm.
 *
 * Owners are never limited: unlike the share history endpoint there is no
 * retention window (`history_seconds` is always null) and the battery state is
 * always included.
 *
 * `?source=raw` asks for the measurements instead of the optimized track — the
 * detail view offers both. Anything else (including no value) means the
 * optimized track, which is what a map should normally draw. See [deviceTrack].
 */
fun Route.getDeviceHistory() {
    val db by inject<DatabaseManager>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        get {
            val actor = call.deviceActor(db)
                ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deviceId = call.parameters["deviceId"]?.let(Uuid::parseOrNull)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val source = when (call.request.queryParameters["source"]) {
                "raw" -> TrackSource.Raw
                else -> TrackSource.Optimized
            }

            // Resolve + ownership-check + read in one transaction; null means the
            // device is missing, already deleted, or not the caller's — all
            // answered as Forbidden so foreign device ids cannot be probed for.
            val points = db.transaction {
                val device = Device.findById(deviceId) ?: return@transaction null
                if (device.owner.id.value != actor.userId) return@transaction null
                if (device.deletion != null) return@transaction null

                deviceTrack(device, source = source).map { it.toHistoryPoint(includeBattery = true) }
            } ?: return@get call.respond(HttpStatusCode.Forbidden)

            call.respond(LocationHistoryResponse(historySeconds = null, points = points))
        }
    }
}
