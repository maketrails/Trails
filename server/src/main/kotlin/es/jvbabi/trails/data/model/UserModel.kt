package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.User
import kotlin.uuid.Uuid
import es.jvbabi.trails.api.v1.entity.User as UserEntity

/**
 * An account.
 *
 * Carries no credentials on purpose: the password hash and the TOTP secret never
 * leave the data layer, they are only ever *checked* there (see
 * [es.jvbabi.trails.data.UserRepository.verifyPassword]).
 */
data class UserModel(
    val id: Uuid,
    val username: String,
    val email: String,
)

/** Must be called inside a transaction. */
fun User.toModel() = UserModel(
    id = id.value,
    username = username,
    email = email,
)

/** The account as the API hands it out — the e-mail address is not part of it. */
fun UserModel.toApi() = UserEntity(
    id = id,
    username = username,
)
