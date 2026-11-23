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

    val url = URLBuilder(baseUrl)
    val storage = File(storageDirectory).apply { mkdirs() }
    val deviceImages = this.storage.resolve("device-images").apply { mkdirs() }
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
            @SerialName("scopes") val scopes: List<String> = listOf("openid", "profile", "email"),
            /**
             * The provider's JSON Web Key Set (`jwks_uri` from its discovery document), used to verify the
             * signature of the ID token. Required as long as [scopes] contains `openid`.
             */
            @SerialName("jwks_uri") val jwksUri: String? = null,
            /** Expected `iss` claim of the ID token. The issuer is not checked if unset. */
            @SerialName("issuer") val issuer: String? = null,
        )
    }
}