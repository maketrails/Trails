package es.jvbabi.trails.data

import java.io.Closeable

/**
 * Resolves coordinates into a human-readable address. Implementations may back
 * onto different providers (e.g. [NominatimService]).
 *
 * This is the raw provider and does no caching of its own — callers go through
 * [ReverseGeocodingRepository], which persists the results.
 */
interface ReverseGeocoding : Closeable {
    /**
     * Resolves [latitude]/[longitude] into an address in [language] (a primary
     * language subtag like `en`).
     *
     * @return the address, or `null` if the provider has none for this position.
     * @throws Exception if the provider could not be asked (network error,
     *   rate limit, …). Unlike `null`, this is not an answer and must not be cached.
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double, language: String): GeocodedAddress?

    override fun close() {}
}

data class GeocodedAddress(
    val road: String?,
    val houseNumber: String?,
    val postcode: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val displayName: String?,
) {
    /**
     * A label like "Musterstraße 12, Leipzig, Sachsen, Deutschland" built from
     * the available components, falling back to the full display name.
     */
    val shortLabel: String
        get() {
            val street = listOfNotNull(road, houseNumber).joinToString(" ").ifBlank { null }
            val parts = listOfNotNull(street, city, state, country)
            return parts.joinToString(", ").ifBlank { null } ?: displayName ?: "Unbekannter Ort"
        }
}
