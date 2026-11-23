package es.jvbabi.trails.routes.auth.app_authorization

import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.trails.auth.Destination
import es.jvbabi.trails.auth.TrailsAuthentiktUser
import es.jvbabi.trails.auth.deviceManufacturerAttribute
import es.jvbabi.trails.auth.deviceModelAttribute
import es.jvbabi.trails.auth.loginDestinationAttribute
import es.jvbabi.trails.config.ApplicationConfig
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.appAuthorization() {
    get {
        val authentikt by inject<AuthentiktInstance<TrailsAuthentiktUser>>()
        val applicationConfig by inject<ApplicationConfig>()
        val session = authentikt.createNewSession()
        session.attributes[loginDestinationAttribute] = Destination.App

        val deviceManufacturer = call.parameters["device_manufacturer"]
        val deviceModel = call.parameters["device_model"]

        session.publicAttributes[deviceManufacturerAttribute] = deviceManufacturer.orEmpty()
        session.publicAttributes[deviceModelAttribute] = deviceModel.orEmpty()

        val destination = URLBuilder(applicationConfig.url).apply {
            appendPathSegments("auth", "authorize")
            parameters.append("_authentikt_flow_active", "true")
            parameters.append("_authentikt_session_id", session.sessionId)
        }.buildString()

        call.respondRedirect(destination)
    }
}