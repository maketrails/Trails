package es.jvbabi.trails.routes.share.item

import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.model.ShareModel
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.server.application.ApplicationCall
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getShare(): ShareModel {
    val shareRepository by inject<ShareRepository>()
    val shareId = parameters["shareId"]?.let(Uuid::parseOrNull) ?: throw EntityNotFoundException("Share not found")
    return shareRepository.getById(shareId) ?: throw EntityNotFoundException("Share not found")
}
