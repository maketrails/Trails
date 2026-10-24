package es.jvbabi.trails.di

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import es.jvbabi.trails.BuildKonfig
import es.jvbabi.trails.data.database.TrailsDatabase
import es.jvbabi.trails.data.database.converter.InstantConverter
import es.jvbabi.trails.data.database.converter.UuidConverter
import es.jvbabi.trails.data.remote.TrailsApi
import es.jvbabi.trails.data.remote.WsHandshakeDiagnostics
import es.jvbabi.trails.data.repository.*
import es.jvbabi.trails.domain.extension.Settings
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.domain.usecase.SetupNotificationsUseCase
import es.jvbabi.trails.domain.usecase.auth.HandleDeepLinkUseCase
import es.jvbabi.trails.domain.usecase.auth.LoginUseCase
import es.jvbabi.trails.domain.usecase.communication.StartExternalConnectionsUseCase
import es.jvbabi.trails.domain.usecase.communication.StopExternalConnectionsUseCase
import es.jvbabi.trails.domain.usecase.home.GetHomeDeviceLocationsUseCase
import es.jvbabi.trails.page.connection_events.ConnectionEventsViewModel
import es.jvbabi.trails.page.devices.device.DeviceViewModel
import es.jvbabi.trails.page.devices.main.DevicesViewModel
import es.jvbabi.trails.page.home.HomeViewModel
import es.jvbabi.trails.page.home.MapViewModel
import es.jvbabi.trails.page.ringing.RingingViewModel
import es.jvbabi.trails.page.setings.SettingsViewModel
import es.jvbabi.trails.page.shares.add_share.AddShareViewModel
import es.jvbabi.trails.page.shares.new_share.NewShareViewModel
import es.jvbabi.trails.ui.overlay.device_deleted.DeviceDeletedViewModel
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

expect fun getDatabaseBuilder(): RoomDatabase.Builder<TrailsDatabase>

/**
 * Everything only one platform has, and that therefore cannot be declared in the shared module —
 * the Android in-app updater, for instance, which iOS has no counterpart for.
 *
 * This is for dependencies whose *implementation as well as their interface* is platform-specific.
 * A dependency that the shared code uses through a common interface belongs in the module the
 * platform's entry point (`MainApplication` / `MainViewController`) passes to [initKoin] instead,
 * since only that knows the platform's context.
 */
expect fun platformModule(): Module

/**
 * The engine of the [HttpClient] for hosts we don't control (see [KOIN_HTTP_CLIENT_THIRD_PARTY]).
 *
 * Chosen per platform rather than left to whichever engine is on the classpath, because those hosts
 * don't play by our rules: GitHub answers a release download with a redirect whose
 * `Content-Security-Policy` header is longer than the 8,192 characters a CIO header line may have,
 * so the CIO engine fails before it ever reaches the file.
 */
expect fun thirdPartyHttpClientEngine(): HttpClientEngineFactory<*>

/** Qualifier of the [HttpClient] used for requests to hosts we don't control. */
const val KOIN_HTTP_CLIENT_THIRD_PARTY = "http_client_third_party"

private val jsonInstance = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()

    modules(module {
        single {
            getDatabaseBuilder()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(TrailsDatabase.Migration2to3, TrailsDatabase.Migration3to4)
                .addTypeConverter(UuidConverter())
                .addTypeConverter(InstantConverter())
                .build()
        }

        single<HttpClient> {
            HttpClient {
                install(ContentNegotiation) {
                    // Keeps ContentNegotiation from trying to (de)serialize the WebSocket session
                    // itself during the WS handshake
                    // ("Serializer for class 'DefaultClientWebSocketSession' is not found").
                    ignoreType<DefaultClientWebSocketSession>()
                    json(jsonInstance)
                }

                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(jsonInstance)
                    pingInterval = 10.seconds
                }

                install(SSE)

                install(WsHandshakeDiagnostics)


                defaultRequest {
                    if (BuildKonfig.WERKBANK_TOKEN != null) {
                        header("Werkbank-No-Browser", "true")
                        header("Werkbank-Access-Token", BuildKonfig.WERKBANK_TOKEN)
                    }
                }
            }
        }

        // Client for third-party APIs (e.g. GitHub). Deliberately carries no Werkbank headers,
        // so the access token can never leak to a host outside our own infrastructure.
        single<HttpClient>(named(KOIN_HTTP_CLIENT_THIRD_PARTY)) {
            HttpClient(thirdPartyHttpClientEngine()) {
                install(ContentNegotiation) {
                    json(jsonInstance)
                }
            }
        }

        singleOf(::TrailsApi)
        singleOf(::UiRepositoryImpl) bind UiRepository::class
        singleOf(::KeyValueRepositoryImpl) bind KeyValueRepository::class
        singleOf(::Settings)
        singleOf(::LocationRepositoryImpl) bind LocationRepository::class
        singleOf(::DevicesRepositoryImpl) bind DevicesRepository::class
        singleOf(::UserRepositoryImpl) bind UserRepository::class
        singleOf(::SnapshotRepositoryImpl) bind SnapshotRepository::class
        singleOf(::TrailsServerRepositoryImpl) bind TrailsServerRepository::class
        singleOf(::ShareRepositoryImpl) bind ShareRepository::class

        singleOf(::SetupNotificationsUseCase)

        singleOf(::HandleDeepLinkUseCase)
        singleOf(::LoginUseCase)

        singleOf(::StartExternalConnectionsUseCase)
        singleOf(::StopExternalConnectionsUseCase)
        singleOf(::GetHomeDeviceLocationsUseCase)

        viewModelOf(::HomeViewModel)
        viewModelOf(::MapViewModel)
        viewModelOf(::RingingViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::NewShareViewModel)
        viewModelOf(::AddShareViewModel)
        viewModelOf(::DevicesViewModel)
        viewModelOf(::DeviceViewModel)
        viewModelOf(::ConnectionEventsViewModel)
        viewModelOf(::DeviceDeletedViewModel)
    })

    modules(platformModule())
}