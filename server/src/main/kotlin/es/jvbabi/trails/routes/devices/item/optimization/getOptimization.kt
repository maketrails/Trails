package es.jvbabi.trails.routes.devices.item.optimization

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.v1.optimization.DeviceOptimizationResponse
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.routes.devices.item.deviceActor
import es.jvbabi.trails.routes.devices.item.ownDevice
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * `GET /devices/{deviceId}/optimization` — the state of the track optimization
 * of one of the caller's **own** devices: how much has been optimized, how much
 * is still raw, and how far a run has got.
 *
 * Only for own devices. A share hands out a track, not the machinery behind it,
 * and the counts would leak how much history exists beyond the share's window.
 */
fun Route.getDeviceOptimization() {
    val db by inject<DatabaseManager>()
    val trailOptimizerScheduler by inject<TrailOptimizerScheduler>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        get {
            val actor = call.deviceActor(db)
                ?: return@get call.respond(HttpStatusCode.Forbidden)
            val device = call.ownDevice(db, actor.userId)
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            val optimizer = db.transaction { trailOptimizerScheduler.optimizerFor(device) }
            val state = optimizer.state()

            call.respond(
                DeviceOptimizationResponse(
                    optimizedPoints = state.optimizedPoints,
                    unoptimizedPoints = state.unoptimizedPoints,
                    rawPoints = state.rawPoints,
                    optimizedDistanceMeters = state.optimizedDistanceMeters,
                    unoptimizedDistanceMeters = state.unoptimizedDistanceMeters,
                    rawDistanceMeters = state.rawDistanceMeters,
                    progress = state.progress,
                    isRunning = state.isRunning,
                )
            )
        }
    }
}
