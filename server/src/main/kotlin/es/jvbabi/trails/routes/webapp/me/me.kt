package es.jvbabi.trails.routes.webapp.me

import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.config.ApplicationConfig
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.webappMe() {
    authenticate(TRAILS_WEBAPP_REALM) {
        get {
            val applicationConfig by inject<ApplicationConfig>()
            val user = call.principal<TrailsWebappPrincipal>()!!.user
            call.respond(
                WebappMeResponse(
                    id = user.id,
                    username = user.username,
                    homeserver = applicationConfig.url.host,
                )
            )
        }
    }
}

@Serializable
data class WebappMeResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("username") val username: String,
    @SerialName("homeserver") val homeserver: String,
)
