package es.jvbabi.trails

import es.jvbabi.trails.data.TrackPipeline
import es.jvbabi.trails.data.TrackPipeline.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What [TrackPipeline.process] does to the shape of a track: corners and curves keep
 * their shape, spikes of one to three positions go, and a real U-turn stays.
 *
 * Tracks are laid out in metres on a local plane — x east, y north — and converted to
 * coordinates around a fixed origin, so the expectations read as distances.
 */
class TrackShapeTest {

    @Test
    fun `a right-angle corner on a bike keeps its corner`() {
        // 18 km/h, a fix every ten seconds: 50 m between two fixes.
        val path = listOf(Point(0.0, 0.0), Point(500.0, 0.0), Point(500.0, 500.0))
        val result = optimize(along(path, metersPerSecond = 5.0, intervalSeconds = 10.0))

        assertNear(Point(500.0, 0.0), result, toleranceMeters = 3.0)
        assertFollows(path, result, toleranceMeters = 3.0)
    }

    @Test
    fun `a right-angle corner on foot keeps its corner despite jitter`() {
        // 5 km/h, a fix every two seconds, each one up to 2 m off.
        val path = listOf(Point(0.0, 0.0), Point(150.0, 0.0), Point(150.0, 150.0))
        val result = optimize(along(path, metersPerSecond = 1.4, intervalSeconds = 2.0, jitterMeters = 2.0))

        assertNear(Point(150.0, 0.0), result, toleranceMeters = 4.0)
        assertFollows(path, result, toleranceMeters = 4.0)
    }

    @Test
    fun `a smooth curve is not cut`() {
        // A half circle of 80 m radius between two straights, ridden at 18 km/h with a
        // fix every two seconds. A line fitted over 40 s of it sits about 12 m inside.
        val radius = 80.0
        val arc = (0..36).map { step ->
            val angle = PI / 2 - PI * step / 36
            Point(300.0 + radius * cos(angle), radius * sin(angle))
        }
        val path = listOf(Point(0.0, radius)) + arc + Point(0.0, -radius)
        val result = optimize(along(path, metersPerSecond = 5.0, intervalSeconds = 2.0))

        assertFollows(path, result, toleranceMeters = 5.0)

        val reach = result.maxOf { it.x }
        assertTrue(reach >= 300 + radius - 5, "the curve reaches only ${"%.1f".format(reach)} m east")
    }

    @Test
    fun `a single spike is removed`() {
        val track = walkEast().toMutableList()
        track[15] = track[15].shifted(north = 80.0)

        assertFollows(EAST, optimize(track), toleranceMeters = 5.0)
    }

    @Test
    fun `two bad positions in a row are removed`() {
        val track = walkEast().toMutableList()
        track[15] = track[15].shifted(north = 80.0)
        track[16] = track[16].shifted(north = 95.0)

        assertFollows(EAST, optimize(track), toleranceMeters = 5.0)
    }

    @Test
    fun `three bad positions in a row are removed`() {
        val track = walkEast().toMutableList()
        track[15] = track[15].shifted(north = 60.0)
        track[16] = track[16].shifted(north = 140.0)
        track[17] = track[17].shifted(north = 70.0)

        assertFollows(EAST, optimize(track), toleranceMeters = 5.0)
    }

    @Test
    fun `a spike far ahead in the direction of travel is removed`() {
        val track = walkEast().toMutableList()
        track[15] = track[15].shifted(east = 90.0)

        val result = optimize(track)

        assertFollows(EAST, result, toleranceMeters = 5.0)
        assertTrue(
            result.zipWithNext().all { (first, second) -> second.x >= first.x - 5 },
            "the track runs east without jumping ahead and back"
        )
    }

    @Test
    fun `a sharp U-turn with consistent positions around it stays`() {
        // Out to x = 500 and back on the other lane, 8 m to the north, at 18 km/h with a
        // fix every ten seconds. The turning point is 50 m out from both neighbours,
        // exactly the shape of a spike — but the track leads into it and out of it.
        val path = listOf(Point(0.0, 0.0), Point(500.0, 0.0), Point(450.0, 8.0), Point(0.0, 8.0))
        val result = optimize(along(path, metersPerSecond = 5.0, intervalSeconds = 10.0))

        assertNear(Point(500.0, 0.0), result, toleranceMeters = 3.0)
    }

    // --- building tracks ---------------------------------------------------------

    /** A position on the local plane, in metres from the origin. */
    private data class Point(val x: Double, val y: Double) {
        fun distanceTo(other: Point) = hypot(x - other.x, y - other.y)
    }

    private val Position.x get() = (longitude - LONGITUDE) * METERS_PER_DEGREE_LONGITUDE
    private val Position.y get() = (latitude - LATITUDE) * METERS_PER_DEGREE_LATITUDE
    private val Position.point get() = Point(x, y)

    private fun Position.shifted(east: Double = 0.0, north: Double = 0.0) = copy(
        latitude = latitude + north / METERS_PER_DEGREE_LATITUDE,
        longitude = longitude + east / METERS_PER_DEGREE_LONGITUDE,
    )

    private fun optimize(track: List<Position>) = TrackPipeline.process(track).flatMap { it.positions }

    /** Ten minutes at 5 km/h heading east, a fix every ten seconds. */
    private fun walkEast() = along(EAST, metersPerSecond = 1.4, intervalSeconds = 10.0)

    /**
     * Fixes along [path] at a constant [metersPerSecond], one every [intervalSeconds],
     * each up to [jitterMeters] off in a fixed pseudo-random direction.
     */
    private fun along(
        path: List<Point>,
        metersPerSecond: Double,
        intervalSeconds: Double,
        jitterMeters: Double = 0.0,
    ): List<Position> {
        val length = path.zipWithNext().sumOf { (first, second) -> first.distanceTo(second) }
        val steps = (length / (metersPerSecond * intervalSeconds)).toInt()

        return (0..steps).map { step ->
            val point = pointAt(path, step * metersPerSecond * intervalSeconds)

            // A fixed sequence, so a failing test fails the same way every time.
            val angle = step * 2.399963
            val offset = jitterMeters * ((step * 7919) % 10) / 10.0

            Position(
                timestamp = START + (step * intervalSeconds * 1000).toLong().milliseconds,
                latitude = LATITUDE + (point.y + offset * sin(angle)) / METERS_PER_DEGREE_LATITUDE,
                longitude = LONGITUDE + (point.x + offset * cos(angle)) / METERS_PER_DEGREE_LONGITUDE,
                accuracy = 5.0,
                bearing = 0.0,
                bearingAccuracy = null,
                batteryLevel = null,
                batteryCharging = null,
            )
        }
    }

    /** The point [meters] along [path]. */
    private fun pointAt(path: List<Point>, meters: Double): Point {
        var left = meters

        for ((first, second) in path.zipWithNext()) {
            val length = first.distanceTo(second)

            if (left <= length) {
                val share = left / length
                return Point(first.x + share * (second.x - first.x), first.y + share * (second.y - first.y))
            }

            left -= length
        }

        return path.last()
    }

    /** Asserts that some position of [result] lies within [toleranceMeters] of [point]. */
    private fun assertNear(point: Point, result: List<Position>, toleranceMeters: Double) {
        val closest = result.minOf { it.point.distanceTo(point) }

        assertTrue(closest <= toleranceMeters, "closest position is ${"%.1f".format(closest)} m from $point")
    }

    /** Asserts that every position of [result] lies within [toleranceMeters] of [path]. */
    private fun assertFollows(path: List<Point>, result: List<Position>, toleranceMeters: Double) {
        assertTrue(result.size >= 2, "the track survives")

        for (position in result) {
            val off = path.zipWithNext().minOf { (first, second) -> distanceToSegment(position.point, first, second) }

            assertTrue(off <= toleranceMeters, "position ${position.point} is ${"%.1f".format(off)} m off the path")
        }
    }

    private fun distanceToSegment(point: Point, first: Point, second: Point): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val lengthSquared = dx * dx + dy * dy

        val share = if (lengthSquared == 0.0) 0.0
        else max(0.0, min(1.0, ((point.x - first.x) * dx + (point.y - first.y) * dy) / lengthSquared))

        return point.distanceTo(Point(first.x + share * dx, first.y + share * dy))
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-29T08:00:00Z")
        const val LATITUDE = 52.4
        const val LONGITUDE = 13.1
        const val METERS_PER_DEGREE_LATITUDE = 6_371_000.0 * PI / 180
        val METERS_PER_DEGREE_LONGITUDE = METERS_PER_DEGREE_LATITUDE * cos(LATITUDE * PI / 180)

        /** 840 m heading east from the origin. */
        val EAST = listOf(Point(0.0, 0.0), Point(840.0, 0.0))
    }
}
