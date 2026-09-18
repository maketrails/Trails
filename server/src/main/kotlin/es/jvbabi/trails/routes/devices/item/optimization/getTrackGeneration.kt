package es.jvbabi.trails.routes.devices.item.optimization

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.v1.optimization.TrackGenerationResponse
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.routes.devices.item.deviceActor
import es.jvbabi.trails.routes.devices.item.ownDevice
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * `GET /devices/{deviceId}/optimization/generation` — when the optimized track of one
 * of the caller's **own** devices was last rebuilt from scratch.
 *
 * A single-row lookup, meant to be asked before the history is read: a client
 * compares it with what its cache was filled under and drops the cache on a mismatch.
 */
fun Route.getTrackGeneration() {
    val deviceRepository by inject<DeviceRepository>()
    val trailOptimizerScheduler by inject<TrailOptimizerScheduler>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        get {
            val actor = call.deviceActor()
                ?: return@get call.respond(HttpStatusCode.Forbidden)
            val device = call.ownDevice(deviceRepository, actor.userId)
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            val rebuiltAt = trailOptimizerScheduler.optimizerFor(device).rebuiltAt()

            call.respond(TrackGenerationResponse(rebuiltAt = rebuiltAt?.toEpochMilliseconds()))
        }
    }
}
