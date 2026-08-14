package es.jvbabi.trails.routes.webapp.optimization

import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.event.UserEvent
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * Optimization progress for all devices the caller owns.
 *
 * Kept apart from the device-update socket on purpose: progress arrives after
 * every batch of positions, while a device update re-sends the whole device list
 * (including reverse geocoding). Mixing the two would tie a progress bar to that
 * cost, and a client that does not show the optimization would pay for it too —
 * here it simply does not open this socket.
 *
 * Web only: the app draws no tracks, so it has nothing to do with this.
 */
fun Route.webappOptimizationSocket() {
    val userRepository by inject<UserRepository>()

    authenticate(TRAILS_WEBAPP_REALM) {
        webSocket {
            val user = call.principal<TrailsWebappPrincipal>()?.user
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthenticated"))

            // Progress is published into the owner's stream, so subscribing to it is
            // the whole ownership check: another user's devices never appear here.
            userRepository.events(user.id)
                .filterIsInstance<UserEvent.OptimizationProgressed>()
                .onEach { event ->
                    sendSerialized<OptimizationSocketMessage>(
                        OptimizationSocketMessage.Progress(
                            deviceId = event.deviceId,
                            progress = event.progress,
                            isRunning = event.isRunning,
                        )
                    )
                }
                .collect()
        }
    }
}

@Serializable
sealed class OptimizationSocketMessage {
    /**
     * How far the optimizer has got on one of the user's own devices. There is
     * no initial snapshot — the numbers come from
     * `GET /devices/{deviceId}/optimization`, and this only reports change.
     */
    @SerialName("optimization.progress")
    @Serializable
    data class Progress(
        @SerialName("device_id") val deviceId: Uuid,
        /** Share of the settled positions that are optimized, 0..1. */
        @SerialName("progress") val progress: Double,
        @SerialName("is_running") val isRunning: Boolean,
    ) : OptimizationSocketMessage()
}
