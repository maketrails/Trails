package es.jvbabi.trails.config

import es.jvbabi.trails.api.jsonInstance
import io.ktor.http.URLBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.random.Random

class ApplicationConfig(
    storageDirectory: String = "./data",
) {

    private val configFile = File(storageDirectory).resolve("config.json")
    private val configContent = configFile.readText()
    val config = jsonInstance.decodeFromString<ApplicationConfigFile>(configContent)

    private val baseUrl = config.baseUrl
    val database = config.database ?: ApplicationConfigFile.Database.Sqlite(path = File(storageDirectory).resolve("database.db").absolutePath)
    val mapboxAccessToken = config.mapbox?.accessToken
    val nominatimBaseUrl = config.nominatim?.baseUrl ?: ApplicationConfigFile.Nominatim.DEFAULT_BASE_URL
    val elevation = config.elevation ?: ApplicationConfigFile.Elevation()

    val url = URLBuilder(baseUrl)
    val storage = File(storageDirectory).apply { mkdirs() }
    val deviceImages = this.storage.resolve("device-images").apply { mkdirs() }

    /** Downloaded elevation tiles, see [ApplicationConfigFile.Elevation]. Created on first download. */
    val elevationTiles = this.storage.resolve("elevation-tiles")
    val jwtSecret = storage.resolve("jwt-secret.key")
        .let { file ->
            if (!file.exists()) file.writeText(Random.nextBytes(32).joinToString(separator = "") { "%02x".format(it) })
            file.readText()
        }
}

@Serializable
data class ApplicationConfigFile(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("database") val database: Database? = null,
    @SerialName("auth") val auth: Auth? = null,
    @SerialName("mapbox") val mapbox: Mapbox? = null,
    @SerialName("nominatim") val nominatim: Nominatim? = null,
    @SerialName("elevation") val elevation: Elevation? = null,
) {
    @Serializable
    data class Mapbox(
        @SerialName("access_token") val accessToken: String,
    )

    @Serializable
    data class Nominatim(
        @SerialName("base_url") val baseUrl: String = DEFAULT_BASE_URL,
    ) {
        companion object {
            const val DEFAULT_BASE_URL = "https://nominatim.openstreetmap.org"
        }
    }

    /**
     * Terrain elevation for the trail optimizer, which takes the gradient into account
     * when it tells walking, cycling and motorised travel apart. Tiles are downloaded
     * on demand from [tileUrl] and cached in the `elevation-tiles` folder of the data
     * directory. Without elevation, every track is classified as if it were flat.
     */
    @Serializable
    data class Elevation(
        @SerialName("enabled") val enabled: Boolean = true,
        /** Terrarium encoded PNG tiles; `{z}`, `{x}` and `{y}` are replaced by the tile coordinates. */
        @SerialName("tile_url") val tileUrl: String = DEFAULT_TILE_URL,
    ) {
        companion object {
            const val DEFAULT_TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        }
    }

    @Serializable
    sealed class Database {
        @Serializable
        @SerialName("sqlite")
        data class Sqlite(@SerialName("path") val path: String) : Database()

        @Serializable
        @SerialName("postgresql")
        data class Postgresql(
            @SerialName("host") val host: String,
            @SerialName("port") val port: Int,
            @SerialName("database") val database: String,
            @SerialName("username") val username: String,
            @SerialName("password") val password: String,
        ) : Database()
    }

    @Serializable
    data class Auth(
        @SerialName("oauth") val oauth: OAuth? = null,
    ) {
        @Serializable
        data class OAuth(
            @SerialName("client_id") val clientId: String,
            @SerialName("client_secret") val clientSecret: String,
            @SerialName("authorization_endpoint") val authorizationEndpoint: String,
            @SerialName("token_endpoint") val tokenEndpoint: String,
            @SerialName("userinfo_endpoint") val userinfoEndpoint: String,
            @SerialName("redirect_uri") val redirectUri: String,
            @SerialName("scopes") val scopes: List<String> = listOf("openid", "profile", "email")
        )
    }
}