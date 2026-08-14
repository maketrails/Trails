package es.jvbabi.trails.routes.user.item

import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.model.UserModel
import es.jvbabi.trails.routes.EntityNotFoundException
import io.ktor.server.application.ApplicationCall
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getUser(): UserModel {
    val userRepository by inject<UserRepository>()
    val userId = parameters["userId"]?.let(Uuid::parseOrNull) ?: throw EntityNotFoundException("User not found")
    return userRepository.getById(userId) ?: throw EntityNotFoundException("User not found")
}
