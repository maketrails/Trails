package es.jvbabi.trails.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Persisted reverse-geocoding results, so a position is resolved against the
 * geocoding provider once instead of on every snapshot that gets sent out.
 *
 * Positions are bucketed into a grid of 0.0001° (4 decimal places, ≈ 11 m in
 * latitude): far finer than an address changes and still below the accuracy a
 * device usually reports, while a device standing still keeps hitting the same
 * cell. The keys are the coordinates multiplied by
 * [es.jvbabi.trails.data.ReverseGeocodingRepository.COORDINATE_SCALE] and stored
 * as integers, so a lookup is an exact match instead of a comparison of doubles.
 *
 * Addresses depend on the language they were requested in, so the language is
 * part of the key.
 *
 * A missing row means "never looked up". A row with [found] set to `false` means
 * the provider was asked and had no address for the cell — every address column
 * is `null` then.
 *
 * Deliberately not referenced by `data_snapshots`: a snapshot keeps its exact
 * position, and the cell it falls into is derived from it when needed.
 */
object ReverseGeocodings : Table("reverse_geocoding") {
    val latitudeKey = integer("latitude_key")
    val longitudeKey = integer("longitude_key")

    /** The language the address was requested in, as a primary language subtag (`en`, `de`). */
    val language = varchar("language", 8)

    val found = bool("found")
    val road = text("road").nullable()
    val houseNumber = text("house_number").nullable()
    val postcode = text("postcode").nullable()
    val city = text("city").nullable()
    val state = text("state").nullable()
    val country = text("country").nullable()
    val displayName = text("display_name").nullable()

    /** When the provider was asked, so stale entries can be refreshed. */
    val resolvedAt = timestamp("resolved_at")

    override val primaryKey = PrimaryKey(latitudeKey, longitudeKey, language)
}
