package es.jvbabi.trails.routes.ring

import es.jvbabi.trails.api.TRAILS_USER_REALM
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.event.DeviceEvent
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * Ring-state channel for a single device. Scoped to the `{deviceId}` under which
 * it is mounted, so a UI only opens it while that device's detail view is on
 * screen — there is no global ring socket.
 *
 * Kept separate from the device-update socket and from the command endpoints so
 * ring state has one authoritative source: the target device confirms start/stop
 * (via the app socket), the repository publishes that as
 * [DeviceEvent.RingStateChanged], and every UI (app and web) reflects the confirmed
 * state.
 *
 * Generic for both realms so the web (cookie) and, if ever needed, the app
 * (bearer) can consume it.
 */
fun Route.ringSocket() {
    val deviceRepository by inject<DeviceRepository>()

    authenticate(TRAILS_USER_REALM, TRAILS_WEBAPP_REALM) {
        webSocket {
            val userId = call.principal<TrailsAppUserPrincipal>()?.user?.id
                ?: call.principal<TrailsWebappPrincipal>()?.user?.id
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthenticated"))

            val deviceId = call.parameters["deviceId"]?.let(Uuid::parseOrNull)
                ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid device id"))

            // The ring state is only meaningful for a device the caller still has.
            val device = deviceRepository.getOwnedById(deviceId, userId)
            if (device == null || device.isDeleted) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Forbidden"))
            }

            // Subscribe to confirmed ring-state changes first, so no update that lands
            // while we send the initial state is missed.
            val streamer = launch {
                deviceRepository.events(deviceId)
                    .filterIsInstance<DeviceEvent.RingStateChanged>()
                    .onEach { event ->
                        sendSerialized<RingSocketMessage>(
                            RingSocketMessage.RingState(
                                deviceId = event.deviceId.toString(),
                                isRinging = event.isRinging,
                                ringedBy = event.requestedByName,
                            )
                        )
                    }
                    .collect()
            }

            // Send the current ring state so a UI that (re)connects while the device is
            // already ringing is up to date — an event stream alone cannot say that.
            deviceRepository.ringRequestedBy(deviceId)?.let { ringedBy ->
                sendSerialized<RingSocketMessage>(
                    RingSocketMessage.RingState(deviceId.toString(), isRinging = true, ringedBy = ringedBy)
                )
            }

            // Keep the connection open until the client disconnects; inbound
            // frames are ignored (this socket is server-push only).
            for (frame in incoming) { /* ignore */ }
            streamer.cancel()
        }
    }
}

@Serializable
sealed class RingSocketMessage {
    @SerialName("ring.state")
    @Serializable
    data class RingState(
        @SerialName("device_id") val deviceId: String,
        @SerialName("is_ringing") val isRinging: Boolean,
        @SerialName("ringed_by") val ringedBy: String,
    ) : RingSocketMessage()
}
