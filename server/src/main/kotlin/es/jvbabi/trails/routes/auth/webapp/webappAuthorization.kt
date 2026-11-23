package es.jvbabi.trails.routes.auth.webapp

import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.trails.auth.Destination
import es.jvbabi.trails.auth.TrailsAuthentiktUser
import es.jvbabi.trails.auth.loginDestinationAttribute
import es.jvbabi.trails.config.ApplicationConfig
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.webappAuthorization() {
    get {
        val authentikt by inject<AuthentiktInstance<TrailsAuthentiktUser>>()
        val applicationConfig by inject<ApplicationConfig>()
        val session = authentikt.createNewSession()
        session.attributes[loginDestinationAttribute] = Destination.Webapp

        val destination = URLBuilder(applicationConfig.url).apply {
            appendPathSegments("auth", "authorize")
            parameters.append("_authentikt_flow_active", "true")
            parameters.append("_authentikt_session_id", session.sessionId)
        }.buildString()

        call.respondRedirect(destination)
    }
}