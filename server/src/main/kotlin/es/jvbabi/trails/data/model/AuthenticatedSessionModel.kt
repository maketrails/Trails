package es.jvbabi.trails.data.model

/**
 * Everything a bearer token identifies: the session it belongs to, the device that
 * holds it and the account that owns them.
 *
 * Resolved in one go ([es.jvbabi.trails.data.SessionRepository.findByToken]) because
 * authentication needs all three on every request, and reading them apart could see
 * three different moments.
 */
data class AuthenticatedSessionModel(
    val user: UserModel,
    val device: DeviceModel,
    val session: SessionModel,
)
