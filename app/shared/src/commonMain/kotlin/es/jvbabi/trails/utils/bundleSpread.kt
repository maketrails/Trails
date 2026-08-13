package es.jvbabi.trails.utils

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How much ground a bundle actually covers.
 *
 * Bundling is a screen-space question (see [bundleOverlappingPins]), so zoomed far
 * enough out a bundle can stand for devices that are nowhere near each other. Past
 * [BUNDLE_SPREAD_MIN_DISTANCE_METERS] that is worth saying out loud: the map draws
 * the circle the members lie in, and the bundle points at the top of it.
 */

/** The circle a bundle's members lie in. */
data class BundleSpread(
    val center: Location,
    /** In metres. Every member of the bundle lies inside. */
    val radiusMeters: Double,
    /** Due north of the centre, on the circle — where the bundle's marker is anchored. */
    val top: Location,
)

/** Below this the members are close enough that a circle would say nothing. */
const val BUNDLE_SPREAD_MIN_DISTANCE_METERS = 10_000.0

/** Same earth radius the distance measurements use, see [distanceTo]. */
private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun toDegrees(radians: Double): Double = radians / PI * 180.0

/** Longitude folded back into [-180, 180]. */
private fun normalizeLongitude(degrees: Double): Double = ((degrees + 540.0) % 360.0) - 180.0

/** The point halfway along the great circle between [a] and [b]. */
private fun midpointBetween(a: Location, b: Location): Location {
    val latitudeA = toRadians(a.latitude)
    val latitudeB = toRadians(b.latitude)
    val longitudeA = toRadians(a.longitude)
    val longitudeDifference = toRadians(b.longitude - a.longitude)

    val x = cos(latitudeB) * cos(longitudeDifference)
    val y = cos(latitudeB) * sin(longitudeDifference)

    return Location(
        latitude = toDegrees(
            atan2(
                sin(latitudeA) + sin(latitudeB),
                sqrt((cos(latitudeA) + x) * (cos(latitudeA) + x) + y * y),
            )
        ),
        longitude = normalizeLongitude(
            toDegrees(longitudeA + atan2(y, cos(latitudeA) + x))
        ),
    )
}

/** The point [distanceMeters] from [from] along [bearing] (in radians, 0 = north). */
private fun destination(from: Location, bearing: Double, distanceMeters: Double): Location {
    val angular = distanceMeters / EARTH_RADIUS_METERS
    val latitude = toRadians(from.latitude)
    val longitude = toRadians(from.longitude)

    val targetLatitude = asin(
        sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing)
    )
    val targetLongitude = longitude + atan2(
        sin(bearing) * sin(angular) * cos(latitude),
        cos(angular) - sin(latitude) * sin(targetLatitude),
    )

    return Location(
        latitude = toDegrees(targetLatitude),
        longitude = normalizeLongitude(toDegrees(targetLongitude)),
    )
}

/**
 * The middle of [points] — where a bundle too tight for a circle is anchored.
 *
 * Deliberately geographic: a bundle's position must not be read off the screen, or it
 * would only be true for the camera that was up when it was read. Bundles this tight
 * span a few hundred metres, so plain averaging is exact enough; it is wrong only for a
 * group straddling the antimeridian, which no bundle of one household's devices does.
 *
 * [points] must not be empty.
 */
fun averageLocation(points: List<Location>): Location = Location(
    latitude = points.sumOf { it.latitude } / points.size,
    longitude = points.sumOf { it.longitude } / points.size,
)

/**
 * The circle [points] lie in, or `null` when they sit closer together than
 * [minDistanceMeters] and there is nothing to point out.
 *
 * The two members furthest apart set it: they are what "how far apart is this bundle"
 * means, and the diameter it takes to hold both.
 */
fun bundleSpread(
    points: List<Location>,
    minDistanceMeters: Double = BUNDLE_SPREAD_MIN_DISTANCE_METERS,
): BundleSpread? {
    if (points.size < 2) return null

    var widest = 0.0
    var from = points.first()
    var to = points.first()
    // O(n²) over the members of one bundle — a handful at most.
    for (a in points.indices) {
        for (b in a + 1..points.lastIndex) {
            val distance = points[a] distanceTo points[b]
            if (distance <= widest) continue
            widest = distance
            from = points[a]
            to = points[b]
        }
    }

    if (widest <= minDistanceMeters) return null

    // Centred between the two, then widened until nothing sticks out: from three
    // members on, the widest pair alone need not hold the rest.
    val center = midpointBetween(from, to)
    var radius = widest / 2.0
    for (point in points) radius = max(radius, center distanceTo point)

    return BundleSpread(
        center = center,
        radiusMeters = radius,
        top = destination(center, bearing = 0.0, distanceMeters = radius),
    )
}

/**
 * The circle as a closed ring of coordinates, [steps] points around the centre. Drawn
 * in real distances rather than in pixels, so it keeps covering the same ground at
 * every zoom.
 *
 * [scale] draws it smaller than it is, which is what the grow-in animation rides on.
 * The step count is what keeps the outline round rather than visibly many-sided, and
 * a circle costs a few dozen points — cheap next to a location history.
 */
fun BundleSpread.ring(scale: Double = 1.0, steps: Int = 128): List<Location> {
    val radius = radiusMeters * scale
    return (0..steps).map { step ->
        destination(center, bearing = step.toDouble() / steps * 2.0 * PI, distanceMeters = radius)
    }
}
