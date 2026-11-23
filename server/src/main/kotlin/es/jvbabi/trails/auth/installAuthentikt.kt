package es.jvbabi.trails.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.authentikt.core.installAuthentikt
import es.jvbabi.authentikt.core.step.plugins.builtin.*
import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.SessionRepository
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.model.UserModel
import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import util.date.plus
import java.security.MessageDigest
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

val deviceModelAttribute = AttributeKey<String>("device_model")
val deviceManufacturerAttribute = AttributeKey<String>("device_manufacturer")
val authSessionSelectedDeviceIdAttribute = AttributeKey<Uuid>("auth_session_selected_device_id")

/**
 * Where a sign-in flow returns to, set by the route that starts the session.
 *
 * This is deliberately not an Authentikt [es.jvbabi.authentikt.core.session.SessionDestination]: a
 * `SessionDestination.OAuth` makes Authentikt run its own authorization code grant and ignore the redirect of
 * [DonePlugin]'s `onSuccess`, while Trails hands out its own tokens right there.
 */
val loginDestinationAttribute = AttributeKey<LoginDestination>("login_destination")

/**
 * How Authentikt sees an account. Wraps the domain model, not a database entity:
 * the sign-in flow keeps the user around across steps and requests, which an
 * Exposed entity would not survive.
 */
class TrailsAuthentiktUser(user: UserModel): AuthentiktUser<UserModel>(user) {
    override suspend fun getEmail(): String = user.email
    override suspend fun getUsername(): String = user.username
    override suspend fun getDisplayName(): String = user.username
}

fun Application.installAuthentikt() {

    val applicationConfig by inject<ApplicationConfig>()
    val userRepository by inject<UserRepository>()
    val deviceRepository by inject<DeviceRepository>()
    val sessionRepository by inject<SessionRepository>()
    val deviceSelectionAuthentiktPlugin = DeviceSelectionAuthentiktPlugin()
    loadKoinModules(module { single { deviceSelectionAuthentiktPlugin } })

    val instance = installAuthentikt {
        apiPrefix = "/api/v1/auth"
        baseUrl = applicationConfig.config.baseUrl
        uiLoginBaseUrl = URLBuilder(applicationConfig.config.baseUrl).apply {
            appendPathSegments("auth", "authorize")
        }.buildString()

        var emailPlugin: EmailUserSelectionPlugin<UserModel>? = null
        var passwordPlugin: PasswordPlugin<UserModel>? = null
        var totpPlugin: TotpPlugin<UserModel>? = null
        var oauthPlugin: OIDCPlugin<UserModel>? = null

        if (applicationConfig.config.auth?.oauth == null) {
            emailPlugin = EmailUserSelectionPlugin {
                findUserByEmail { email ->
                    userRepository.findByEmailOrUsername(email)?.let { TrailsAuthentiktUser(it) }
                }

                withUsername = true
            }
            install(emailPlugin)

            passwordPlugin = PasswordPlugin {
                // The hash never leaves the data layer; only the verdict comes back.
                checkPassword { user, password ->
                    return@checkPassword userRepository.verifyPassword(user.id, password)
                }
            }
            install(passwordPlugin)

            totpPlugin = TotpPlugin {
                getSecret { user -> userRepository.otpSecret(user.id)!! }
            }
            install(totpPlugin)
        } else {
            oauthPlugin = OIDCPlugin {
                clientId = applicationConfig.config.auth!!.oauth!!.clientId
                clientSecret = applicationConfig.config.auth!!.oauth!!.clientSecret
                authorizationEndpoint = applicationConfig.config.auth!!.oauth!!.authorizationEndpoint
                userInfoEndpoint = applicationConfig.config.auth!!.oauth!!.userinfoEndpoint
                tokenEndpoint = applicationConfig.config.auth!!.oauth!!.tokenEndpoint
                scopes(*applicationConfig.config.auth!!.oauth!!.scopes.toTypedArray())
                jwksUri = applicationConfig.config.auth!!.oauth!!.jwksUri
                issuer = applicationConfig.config.auth!!.oauth!!.issuer

                onUserInfo { response, _ ->
                    // The user info endpoint is always configured, so there is always a response.
                    val map = response!!.body<Map<String, String>>()
                    val user = userRepository.findByEmail(map["email"]!!)
                    if (user == null) return@onUserInfo UserInfo.Result.Failure("user not found")
                    return@onUserInfo UserInfo.Result.Success(TrailsAuthentiktUser(user))
                }
            }
            install(oauthPlugin)
        }

        val donePlugin = DonePlugin<UserModel> {
            onSuccess { session, user ->

                when (session.attributes[loginDestinationAttribute]) {
                    Destination.App -> {
                        val deviceId = session.attributes[authSessionSelectedDeviceIdAttribute]
                        val device = deviceRepository.getById(deviceId!!)!!
                        require(device.ownerId == user.id) { "Device does not belong to user" }

                        val jwt = JWT
                            .create()
                            .withAudience("trails-app")
                            .withIssuer("trails-app-server")
                            .withClaim("user_id", user.id.toString())
                            .withClaim("device_id", device.id.toString())
                            .withExpiresAt(
                                Clock.System.now()
                                    .toLocalDateTime(TimeZone.currentSystemDefault())
                                    .plus(365.days)
                                    .toJavaLocalDateTime()
                                    .toInstant(ZoneOffset.UTC)
                            )
                            .sign(Algorithm.HMAC256(applicationConfig.jwtSecret))

                        sessionRepository.create(
                            deviceId = device.id,
                            tokenHash = MessageDigest.getInstance("SHA-256").digest(jwt.toByteArray()).joinToString("") { "%02x".format(it) },
                        )

                        val url = URLBuilder(Destination.App.redirectUri).apply {
                            parameters.append("token", jwt)
                        }

                        redirect(url.buildString())
                    }

                    Destination.Webapp -> {
                        val validFor = 365.days
                        val validUntil = Clock.System.now().plus(validFor)

                        val jwt = JWT
                            .create()
                            .withAudience("trails-webapp")
                            .withIssuer("trails-app-server")
                            .withClaim("user_id", user.id.toString())
                            .withExpiresAt(
                                validUntil
                                    .toLocalDateTime(TimeZone.currentSystemDefault())
                                    .toJavaLocalDateTime()
                                    .toInstant(ZoneOffset.UTC)
                            )
                            .sign(Algorithm.HMAC256(applicationConfig.jwtSecret))

                        cookie(Cookie(
                            name = "trails-webapp-token",
                            value = jwt,
                            path = "/",
                            secure = true,
                            httpOnly = true,
                            expires = GMTDate(validUntil.toEpochMilliseconds())
                        ))
                        redirect(Destination.Webapp.redirectUri)
                    }

                    null -> error("Session ${session.sessionId} has no login destination")
                }
            }
        }
        install(donePlugin)

        install(deviceSelectionAuthentiktPlugin)

        authorization { session ->
            val user = session.identifiedUser

            if (applicationConfig.config.auth?.oauth == null) {
                when {
                    user == null -> return@authorization emailPlugin!!
                    !session.has(passwordPlugin!!) -> return@authorization passwordPlugin
                    userRepository.otpSecret(user.user.id) != null && !session.has(totpPlugin!!) -> return@authorization totpPlugin
                }
            } else {
                if (user == null) return@authorization oauthPlugin!!
            }

            if (session.attributes[loginDestinationAttribute] == Destination.App) {
                val nextStep = authSessionDeviceSelection(session, user.user)
                if (nextStep != null) return@authorization nextStep
            }

            return@authorization donePlugin
        }
    }

    loadKoinModules(module { single { instance } })
}

/**
 * A client that a sign-in flow can return to, stored in [loginDestinationAttribute].
 *
 * @param redirectUri where the browser is sent once the user is signed in.
 */
data class LoginDestination(val redirectUri: String)

object Destination : KoinComponent {
    private val applicationConfig by inject<ApplicationConfig>()

    val App = LoginDestination(
        redirectUri = URLBuilder().apply {
            protocol = URLProtocol("trailsapp", -1)
            host = "application"
            appendPathSegments(applicationConfig.url.host)
            appendPathSegments("auth", "redirect")
        }.buildString(),
    )

    val Webapp = LoginDestination(
        redirectUri = URLBuilder(applicationConfig.url).apply {
            appendPathSegments("auth", "webapp", "callback")
        }.buildString(),
    )
}