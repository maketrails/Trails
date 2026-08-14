package es.jvbabi.trails.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.data.SessionRepository
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.data.model.SessionModel
import es.jvbabi.trails.data.model.UserModel
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.koin.ktor.ext.inject
import java.security.MessageDigest
import kotlin.uuid.Uuid

const val TRAILS_USER_REALM = "trails"
const val TRAILS_WEBAPP_REALM = "trails-webapp"

fun Application.installAuthentication() {
    val applicationConfig by inject<ApplicationConfig>()
    val sessionRepository by inject<SessionRepository>()
    val userRepository by inject<UserRepository>()

    install(Authentication) {
        jwt(name = TRAILS_USER_REALM) {
            realm = "Trails API"
            verifier(JWT
                .require(Algorithm.HMAC256(applicationConfig.jwtSecret))
                .withAudience("trails-app")
                .withIssuer("trails-app-server")
                .build())

            validate { credential ->
                val originalJwt = (this.request.parseAuthorizationHeader() as HttpAuthHeader.Single).blob
                val jwtHash = MessageDigest.getInstance("SHA-256").digest(originalJwt.toByteArray()).joinToString("") { "%02x".format(it) }
                val userId = Uuid.parse(credential.payload.getClaim("user_id").asString())

                // The token, the device holding it and the account come back together
                // — and only when the session is valid and really that user's.
                sessionRepository.findByToken(tokenHash = jwtHash, ownerId = userId)?.let { authenticated ->
                    TrailsAppUserPrincipal(
                        user = authenticated.user,
                        device = authenticated.device,
                        session = authenticated.session,
                    )
                }
            }
        }

        jwt(name = TRAILS_WEBAPP_REALM) {
            realm = "Trails Webapp"
            verifier(JWT
                .require(Algorithm.HMAC256(applicationConfig.jwtSecret))
                .withAudience("trails-webapp")
                .withIssuer("trails-app-server")
                .build()
            )

            authHeader { call ->
                val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.ifBlank { null }
                if (token != null) {
                    return@authHeader parseAuthorizationHeader("Bearer $token")
                }

                val cookieName = "trails-webapp-token"

                val cookie = call.request.cookies[cookieName]?.ifBlank { null }
                if (cookie != null) {
                    return@authHeader parseAuthorizationHeader("Bearer $cookie")
                }

                return@authHeader null
            }

            validate { credential ->
                val userId = Uuid.parse(credential.payload.getClaim("user_id").asString())
                userRepository.getById(userId)?.let { TrailsWebappPrincipal(it) }
            }
        }
    }
}

/**
 * A signed-in device: which account, which device, which session. Read fresh on
 * every request, so [device] is the state at the time of the call — which is what
 * makes [requireValidSession] a plain check rather than another read.
 */
data class TrailsAppUserPrincipal(
    val user: UserModel,
    val device: DeviceModel,
    val session: SessionModel,
) {
    /**
     * Refuses to serve a session whose device was removed. Its token stays
     * technically valid — the device is what is gone.
     */
    fun requireValidSession() {
        if (device.isDeleted) throw RuntimeException("Device is deleted")
    }
}

/** A browser session: an account, with no device of its own. */
data class TrailsWebappPrincipal(
    val user: UserModel,
)
