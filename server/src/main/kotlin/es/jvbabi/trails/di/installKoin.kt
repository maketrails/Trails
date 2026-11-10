package es.jvbabi.trails.di

import es.jvbabi.trails.ApplicationLaunchConfig
import es.jvbabi.trails.config.ApplicationConfig
import es.jvbabi.trails.data.DeviceInformationRepository
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.SessionRepository
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.MovementRepository
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.NominatimService
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.ReverseGeocodingRepository
import es.jvbabi.trails.data.TrailOptimizerScheduler
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.elevation.ElevationProvider
import es.jvbabi.trails.data.elevation.HttpTileFetcher
import es.jvbabi.trails.data.elevation.TerrainTileElevationProvider
import es.jvbabi.trails.database.DatabaseManager
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin

private val coreModule = module {
    single { DatabaseManager() }
    single { DeviceInformationRepository() }

    // The repositories that own the state and its event streams. Everything above
    // them reads and writes through these; nothing else touches the database.
    single { UserRepository() }
    single { SessionRepository() }
    single { DeviceRepository() }
    single { TrackRepository() }
    single { MovementRepository() }
    single { ShareRepository() }
    single<ReverseGeocoding> { NominatimService() }
    single { ReverseGeocodingRepository() }
    single { TrailOptimizerScheduler() }
    single<ElevationProvider> {
        val config = get<ApplicationConfig>()

        if (!config.elevation.enabled) return@single ElevationProvider.Disabled

        val fetcher = HttpTileFetcher(config.elevation.tileUrl)
        TerrainTileElevationProvider(
            cacheDirectory = config.elevationTiles,
            fetcher = fetcher,
            onClose = fetcher::close,
        )
    }
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

        val elevationProvider by inject<ElevationProvider>()
        elevationProvider.close()
    }
}