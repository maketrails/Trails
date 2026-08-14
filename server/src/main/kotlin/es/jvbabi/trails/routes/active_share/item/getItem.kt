package es.jvbabi.trails.routes.active_share.item

import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.model.ActiveShareModel
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.server.application.ApplicationCall
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getActiveShare(): ActiveShareModel {
    val shareRepository by inject<ShareRepository>()
    val activeShareId = parameters["activeShareId"]?.let(Uuid::parseOrNull)
        ?: throw EntityNotFoundException("Active share not found")
    return shareRepository.getActiveShareById(activeShareId)
        ?: throw EntityNotFoundException("Active share not found")
}
