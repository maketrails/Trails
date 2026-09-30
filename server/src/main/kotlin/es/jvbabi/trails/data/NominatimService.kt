package es.jvbabi.trails.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import es.jvbabi.trails.config.ApplicationConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Reverse-geocodes coordinates into a human-readable address via OpenStreetMap
 * Nominatim.
 *
 * The instance is configurable via `.nominatim.base_url` and defaults to the
 * public OSM instance, which is rate limited to one request per second — which is
 * why results are persisted by [ReverseGeocodingRepository] instead of being
 * requested for every snapshot.
 */
class NominatimService : ReverseGeocoding, KoinComponent {

    private val applicationConfig by inject<ApplicationConfig>()
    private val baseUrl = applicationConfig.nominatimBaseUrl

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double, language: String): GeocodedAddress? {
        val response = httpClient.get(URLBuilder(baseUrl).apply {
            appendPathSegments("reverse")
            parameters.append("lat", latitude.toString())
            parameters.append("lon", longitude.toString())
            parameters.append("format", "jsonv2")
            parameters.append("addressdetails", "1")
        }.buildString()) {
            // Nominatim's usage policy requires an identifying User-Agent.
            header(HttpHeaders.UserAgent, "TrailsApp/1.0 (trails)")
            header(HttpHeaders.AcceptLanguage, language)
        }

        // A failed request (rate limit, server error, …) says nothing about the
        // position, so it is reported as a failure rather than as "no address".
        check(response.status.isSuccess()) { "Nominatim answered ${response.status}" }

        val body = response.body<ReverseResponse>()
        return body.address?.let { addr ->
            GeocodedAddress(
                road = addr.road,
                houseNumber = addr.houseNumber,
                postcode = addr.postcode,
                city = addr.city
                    ?: addr.town
                    ?: addr.municipality
                    ?: addr.suburb
                    ?: addr.hamlet
                    ?: addr.village,
                state = addr.state,
                country = addr.country,
                displayName = body.displayName,
            )
        }
    }

    override fun close() {
        httpClient.close()
    }

    @Serializable
    private data class ReverseResponse(
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("address") val address: Address? = null,
    ) {
        @Serializable
        data class Address(
            @SerialName("house_number") val houseNumber: String? = null,
            @SerialName("road") val road: String? = null,
            @SerialName("postcode") val postcode: String? = null,
            @SerialName("city") val city: String? = null,
            @SerialName("town") val town: String? = null,
            @SerialName("municipality") val municipality: String? = null,
            @SerialName("village") val village: String? = null,
            @SerialName("hamlet") val hamlet: String? = null,
            @SerialName("suburb") val suburb: String? = null,
            @SerialName("state") val state: String? = null,
            @SerialName("country") val country: String? = null,
        )
    }
}
