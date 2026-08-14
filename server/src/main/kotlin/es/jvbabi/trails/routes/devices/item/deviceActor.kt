package es.jvbabi.trails.routes.devices.item

import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.shared.dto.websocket.PingSource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import kotlin.uuid.Uuid

/**
 * The party performing a device action (ping / ring). Ping and ring are generic
 * REST endpoints reachable by both the app (a signed-in device) and the web
 * (a browser session), so the concrete principal is normalised into a common
 * shape: which user owns the action, a human-readable source name for
 * notifications, and where the action originated.
 */
data class DeviceActor(
    val userId: Uuid,
    val sourceName: String,
    val source: PingSource,
)

/**
 * Resolves the authenticated principal (either the app or the webapp realm) into
 * a [DeviceActor], or `null` if neither realm authenticated the call.
 */
suspend fun ApplicationCall.deviceActor(): DeviceActor? {
    principal<TrailsAppUserPrincipal>()?.let { principal ->
        principal.requireValidSession()
        return DeviceActor(
            userId = principal.user.id,
            sourceName = principal.device.displayName,
            source = PingSource.DEVICE,
        )
    }
    principal<TrailsWebappPrincipal>()?.let { principal ->
        return DeviceActor(
            userId = principal.user.id,
            sourceName = "Browser",
            source = PingSource.BROWSER,
        )
    }
    return null
}

/**
 * Resolves the `deviceId` path parameter to a device the given user owns, or
 * `null` when it is missing, unknown or somebody else's. Callers answer `null`
 * as Forbidden either way, so a foreign device id cannot be probed for.
 */
suspend fun ApplicationCall.ownDevice(deviceRepository: DeviceRepository, userId: Uuid): DeviceModel? {
    val deviceId = parameters["deviceId"]?.let(Uuid::parseOrNull) ?: return null
    return deviceRepository.getOwnedById(deviceId, userId)
}
