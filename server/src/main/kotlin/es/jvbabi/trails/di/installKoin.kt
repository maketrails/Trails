package es.jvbabi.trails.di

import es.jvbabi.trails.ApplicationLaunchConfig
import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.data.DeviceInformationRepository
import es.jvbabi.trails.data.DeviceSubscriptionRepository
import es.jvbabi.trails.data.NominatimService
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.data.UserSubscriptionRepository
import es.jvbabi.trails.database.DatabaseManager
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin

private val coreModule = module {
    single { DatabaseManager() }
    single { DeviceInformationRepository() }
    single { DeviceSubscriptionRepository() }
    single { UserSubscriptionRepository() }
    single<ReverseGeocoding> { NominatimService() }
    single { TrailOptimizerScheduler() }
}

fun Application.installKoin(
    applicationLaunchConfig: ApplicationLaunchConfig,
) {
    install(Koin) {
        modules(
            module { single { ApplicationConfig(
                storageDirectory = applicationLaunchConfig.storageDirectory.absolutePath,
            )} },
            coreModule
        )
    }

    monitor.subscribe(ApplicationStopping) {
        val deviceInformationRepository by inject<DeviceInformationRepository>()
        deviceInformationRepository.close()

        val reverseGeocoding by inject<ReverseGeocoding>()
        reverseGeocoding.close()

        val trailOptimizerScheduler by inject<TrailOptimizerScheduler>()
        trailOptimizerScheduler.close()
    }
}