package es.jvbabi.trails.routes.devices.item.history

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.TrackSource
import es.jvbabi.trails.routes.devices.item.deviceActor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a GPX file is served as. */
private val GPX_CONTENT_TYPE = ContentType("application", "gpx+xml")

/**
 * `GET /devices/{deviceId}/history/gpx?start=<epoch millis>&end=<epoch millis>` —
 * the stretch of one of the caller's **own** devices' track between those two
 * instants, as a GPX 1.1 file to download.
 *
 * Both bounds are required and inclusive, and both are *recording* times, which is
 * what the caller marked on the timeline. `?source=raw` exports the measurements
 * instead of the optimized track, the same choice [getDeviceHistory] offers.
 *
 * The file is named after the device and the window it covers, so a folder full of
 * exports stays readable.
 */
fun Route.getDeviceGpx() {
    val deviceRepository by inject<DeviceRepository>()
    val trackRepository by inject<TrackRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        get {
            val actor = call.deviceActor()
                ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deviceId = call.parameters["deviceId"]?.let(Uuid::parseOrNull)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val start = call.request.queryParameters["start"]?.toLongOrNull()
                ?.let(Instant::fromEpochMilliseconds)
                ?: return@get call.respond(HttpStatusCode.BadRequest, "start is required, as epoch milliseconds")
            val end = call.request.queryParameters["end"]?.toLongOrNull()
                ?.let(Instant::fromEpochMilliseconds)
                ?: return@get call.respond(HttpStatusCode.BadRequest, "end is required, as epoch milliseconds")
            if (end < start) return@get call.respond(HttpStatusCode.BadRequest, "end is before start")

            val source = when (call.request.queryParameters["source"]) {
                "raw" -> TrackSource.Raw
                else -> TrackSource.Optimized
            }

            // Missing, already removed, or not the caller's — all answered as
            // Forbidden, so foreign device ids cannot be probed for.
            val device = deviceRepository.getOwnedById(deviceId, actor.userId)
                ?: return@get call.respond(HttpStatusCode.Forbidden)
            if (device.isDeleted) return@get call.respond(HttpStatusCode.Forbidden)

            // Only the lower bound is a query: the repository windows on the recording
            // time from below, and cutting the tail here keeps that one call.
            val track = trackRepository
                .track(deviceId, notOlderThan = start, source = source)
                .filter { it.createdAt <= end }

            val points = track.map { GpxPoint(latitude = it.latitude, longitude = it.longitude, time = it.createdAt) }

            call.response.header(
                HttpHeaders.ContentDisposition,
                attachmentHeader(gpxFileName(device.displayName, start, end)),
            )
            call.respondText(gpxDocument(device.displayName, points), GPX_CONTENT_TYPE)
        }
    }
}
