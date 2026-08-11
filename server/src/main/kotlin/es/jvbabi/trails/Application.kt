package es.jvbabi.trails

import es.jvbabi.trails.api.installAuthentication
import es.jvbabi.trails.api.installCallLogging
import es.jvbabi.trails.api.installContentNegotiation
import es.jvbabi.trails.api.installCors
import es.jvbabi.trails.api.installDefaultHeaders
import es.jvbabi.trails.api.installSse
import es.jvbabi.trails.api.installStatusPages
import es.jvbabi.trails.api.installWebsocket
import es.jvbabi.trails.auth.installAuthentikt
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.di.installKoin
import es.jvbabi.trails.routes.installRouting
import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

fun Application.rootModule(
    applicationLaunchConfig: ApplicationLaunchConfig,
) {
    val logger = LoggerFactory.getLogger("ApplicationInit")
    logger.info("Starting application")
    logger.info("Storage at ${applicationLaunchConfig.storageDirectory}")
    installKoin(applicationLaunchConfig)
    installDefaultHeaders()
    installCors()
    installWebsocket()
    installSse()
    installCallLogging()
    installContentNegotiation()
    installAuthentication()
    installAuthentikt()
    installStatusPages()
    installRouting()

    val trailOptimizerScheduler by inject<TrailOptimizerScheduler>()
    trailOptimizerScheduler.start()
}
