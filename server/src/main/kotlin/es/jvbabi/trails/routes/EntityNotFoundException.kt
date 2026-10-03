package es.jvbabi.trails.routes

import io.ktor.http.HttpStatusCode
import plus.vplan.commonapi.ApiException

/**
 * Thrown by the `ApplicationCall.getEntity()` helpers in the item packages when an entity
 * requested via a path parameter does not exist. The StatusPages installed by commonapi
 * ([es.jvbabi.trails.api.installCommonApi]) answers it with a 404.
 */
class EntityNotFoundException(message: String) : ApiException(message) {
    override val status = HttpStatusCode.NotFound
}
