package es.jvbabi.trails.data

import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.ReverseGeocodings
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Addresses for positions, backed by the `reverse_geocoding` table.
 *
 * The only way the rest of the server should reverse-geocode: it asks the
 * [ReverseGeocoding] provider only for grid cells it has not resolved yet (or
 * resolved too long ago), and stores every answer — including "no address here".
 */
class ReverseGeocodingRepository : KoinComponent {
    companion object {
        /**
         * Coordinates are multiplied by this and rounded to form the grid cell keys:
         * 4 decimal places, ≈ 11 m in latitude.
         */
        const val COORDINATE_SCALE = 10_000.0

        /** After this, a cell is resolved again — OpenStreetMap keeps changing. */
        val MAX_AGE = 90.days

        /** The languages addresses are resolved in, matching the languages the clients ship. */
        val SUPPORTED_LANGUAGES = setOf("en", "de")
        const val DEFAULT_LANGUAGE = "en"

        /**
         * Picks the address language for a client from its preferred languages
         * (e.g. an `Accept-Language` header, most preferred first). Restricted to
         * [SUPPORTED_LANGUAGES], so the cache doesn't split into one copy per
         * regional variant or language nobody renders.
         */
        fun languageFor(preferred: List<String>): String =
            preferred.firstNotNullOfOrNull { tag ->
                tag.substringBefore('-').substringBefore('_').lowercase().takeIf { it in SUPPORTED_LANGUAGES }
            } ?: DEFAULT_LANGUAGE
    }

    private val db by inject<DatabaseManager>()
    private val reverseGeocoding by inject<ReverseGeocoding>()

    /**
     * The address of the grid cell [latitude]/[longitude] falls into, in [language].
     *
     * Resolved against the provider on a cache miss or for a stale entry; the
     * provider is asked for the cell's center, so the stored address doesn't
     * depend on which position in the cell happened to be looked up first.
     * Never called with a database connection held across the network call.
     *
     * @return the address, or `null` if there is none or the provider can't be
     *   reached right now and nothing is stored for the cell yet.
     */
    suspend fun addressFor(latitude: Double, longitude: Double, language: String): GeocodedAddress? {
        val latitudeKey = (latitude * COORDINATE_SCALE).roundToInt()
        val longitudeKey = (longitude * COORDINATE_SCALE).roundToInt()

        val cached = db.transaction {
            ReverseGeocodings.selectAll()
                .where {
                    (ReverseGeocodings.latitudeKey eq latitudeKey) and
                            (ReverseGeocodings.longitudeKey eq longitudeKey) and
                            (ReverseGeocodings.language eq language)
                }
                .singleOrNull()
                ?.let { row ->
                    CachedAddress(
                        address = if (!row[ReverseGeocodings.found]) null else GeocodedAddress(
                            road = row[ReverseGeocodings.road],
                            houseNumber = row[ReverseGeocodings.houseNumber],
                            postcode = row[ReverseGeocodings.postcode],
                            city = row[ReverseGeocodings.city],
                            state = row[ReverseGeocodings.state],
                            country = row[ReverseGeocodings.country],
                            displayName = row[ReverseGeocodings.displayName],
                        ),
                        isStale = Clock.System.now() - row[ReverseGeocodings.resolvedAt] > MAX_AGE,
                    )
                }
        }
        if (cached != null && !cached.isStale) return cached.address

        val address = try {
            reverseGeocoding.reverseGeocode(
                latitude = latitudeKey / COORDINATE_SCALE,
                longitude = longitudeKey / COORDINATE_SCALE,
                language = language,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not an answer, so nothing is stored; a stale entry beats none.
            return cached?.address
        }

        db.transaction {
            // Two lookups of the same cell can race past the read above; the upsert
            // lets the second one simply overwrite with an equivalent answer.
            ReverseGeocodings.upsert {
                it[this.latitudeKey] = latitudeKey
                it[this.longitudeKey] = longitudeKey
                it[this.language] = language
                it[found] = address != null
                it[road] = address?.road
                it[houseNumber] = address?.houseNumber
                it[postcode] = address?.postcode
                it[city] = address?.city
                it[state] = address?.state
                it[country] = address?.country
                it[displayName] = address?.displayName
                it[resolvedAt] = Clock.System.now()
            }
        }
        return address
    }

    private data class CachedAddress(val address: GeocodedAddress?, val isStale: Boolean)
}
