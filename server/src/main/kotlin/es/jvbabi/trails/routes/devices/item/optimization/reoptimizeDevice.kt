package es.jvbabi.trails.routes.devices.item.optimization

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.routes.devices.item.deviceActor
import es.jvbabi.trails.routes.devices.item.ownDevice
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/**
 * The rebuilds triggered by hand. They outlive the request that started them —
 * a full history takes far longer than a browser will wait — so they do not run
 * in the call's scope.
 */
private val reoptimizations = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val logger = LoggerFactory.getLogger("TrailOptimizer")

/**
 * `POST /devices/{deviceId}/optimization/reoptimize` — throws away the optimized
 * track of one of the caller's **own** devices and derives it again from the raw
 * measurements.
 *
 * Answers `202 Accepted` as soon as the run is started: rebuilding a long
 * history takes minutes, and the progress is reported over the webapp socket
 * anyway. The raw measurements are never touched, so this is a safe thing to
 * ask for twice.
 */
fun Route.reoptimizeDevice() {
    val db by inject<DatabaseManager>()
    val trailOptimizerScheduler by inject<TrailOptimizerScheduler>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        post {
            val actor = call.deviceActor(db)
                ?: return@post call.respond(HttpStatusCode.Forbidden)
            val device = call.ownDevice(db, actor.userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val optimizer = db.transaction { trailOptimizerScheduler.optimizerFor(device) }
            val deviceId = device.id.value

            reoptimizations.launch {
                runCatching { optimizer.reoptimize() }
                    .onFailure { error -> logger.warn("Reoptimizing device $deviceId failed", error) }
            }

            call.respond(HttpStatusCode.Accepted)
        }
    }
}
