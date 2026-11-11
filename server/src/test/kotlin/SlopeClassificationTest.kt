package es.jvbabi.trails

import es.jvbabi.trails.data.TrackPipeline
import es.jvbabi.trails.data.TrackPipeline.Position
import es.jvbabi.trails.data.elevation.ElevationLookup
import es.jvbabi.trails.data.model.Movement
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How the gradient changes the movement classification: tracks heading east over a
 * made-up terrain, whose elevation a fake [ElevationLookup] reports — no tiles, no
 * network.
 */
class SlopeClassificationTest {

    private val walking = Movement.Type.Walking
    private val bike = Movement.Type.Bike
    private val travel = Movement.Type.Travel()

    @Test
    fun `cycling uphill at walking speed is a bike ride`() {
        val track = terrain { move(kmh = 10.0, duration = 10.minutes, grade = 0.06) }

        assertEquals(listOf(walking), typesOf(track, ElevationLookup.NONE), "on the flat, 10 km/h is walking")
        assertEquals(listOf(bike), typesOf(track))
    }

    @Test
    fun `cycling down a steep hill at car speed is a bike ride`() {
        val track = terrain { move(kmh = 45.0, duration = 4.minutes, grade = -0.08) }

        assertEquals(listOf(travel), typesOf(track, ElevationLookup.NONE), "on the flat, 45 km/h is travel")
        assertEquals(listOf(bike), typesOf(track))
    }

    @Test
    fun `a ride over a hill stays one bike ride`() {
        val track = terrain {
            move(kmh = 22.0, duration = 4.minutes)
            move(kmh = 9.0, duration = 8.minutes, grade = 0.07)
            move(kmh = 45.0, duration = 2.minutes, grade = -0.07)
            move(kmh = 22.0, duration = 4.minutes)
        }

        assertEquals(listOf(bike), typesOf(track))
    }

    @Test
    fun `walking on the flat is walking`() {
        val track = terrain { move(kmh = 5.0, duration = 10.minutes) }

        assertEquals(listOf(walking), typesOf(track))
    }

    @Test
    fun `walking up a steep hill is walking`() {
        val track = terrain { move(kmh = 4.5, duration = 10.minutes, grade = 0.08) }

        assertEquals(listOf(walking), typesOf(track))
    }

    @Test
    fun `walking downhill is walking`() {
        val track = terrain { move(kmh = 5.5, duration = 10.minutes, grade = -0.08) }

        assertEquals(listOf(walking), typesOf(track))
    }

    @Test
    fun `a car on the flat is travel`() {
        val track = terrain { move(kmh = 60.0, duration = 10.minutes) }

        assertEquals(listOf(travel), typesOf(track))
    }

    @Test
    fun `a car going down a steep hill is travel`() {
        val track = terrain { move(kmh = 80.0, duration = 5.minutes, grade = -0.08) }

        assertEquals(listOf(travel), typesOf(track))
    }

    @Test
    fun `a car going up a hill is travel`() {
        val track = terrain { move(kmh = 50.0, duration = 5.minutes, grade = 0.08) }

        assertEquals(listOf(travel), typesOf(track))
    }

    @Test
    fun `without elevation for every position the track counts as flat`() {
        val track = terrain { move(kmh = 10.0, duration = 10.minutes, grade = 0.06) }
        val known = track.elevation
        val patchy = ElevationLookup { latitude, longitude ->
            if (longitude > track.positions[30].longitude) null else known.elevationAt(latitude, longitude)
        }

        assertEquals(listOf(walking), typesOf(track, patchy))
    }

    // --- building tracks ---------------------------------------------------------

    private fun typesOf(track: Terrain, elevation: ElevationLookup = track.elevation): List<Movement.Type> {
        val update = TrackPipeline.MovementStream().add(
            TrackPipeline.process(track.positions, elevation).flatMap { it.legs }
        )
        return update.runs.map { run -> run.first().mode }
    }

    /** A track heading east, a fix every ten seconds, and the terrain under it. */
    private class Terrain(val positions: List<Position>, val elevation: ElevationLookup)

    private fun terrain(build: TerrainBuilder.() -> Unit): Terrain = TerrainBuilder().apply(build).build()

    private class TerrainBuilder {
        private var time = Instant.parse("2026-09-29T08:00:00Z")
        private var longitude = 13.1
        private var elevation = 200.0

        private val positions = mutableListOf(position())

        /** Where the slope changes, as longitude to elevation; linear in between. */
        private val profile = mutableListOf(longitude to elevation)

        fun move(kmh: Double, duration: Duration, grade: Double = 0.0) {
            repeat((duration.inWholeSeconds / FIX_INTERVAL_SECONDS).toInt()) {
                val meters = kmh / 3.6 * FIX_INTERVAL_SECONDS
                time += FIX_INTERVAL_SECONDS.seconds
                longitude += meters / METERS_PER_DEGREE_LONGITUDE
                elevation += grade * meters
                positions += position()
            }
            profile += longitude to elevation
        }

        fun build(): Terrain {
            val profile = profile.toList()

            // Flat beyond both ends of the track.
            val lookup = ElevationLookup { _, longitude ->
                when {
                    longitude <= profile.first().first -> profile.first().second
                    longitude >= profile.last().first -> profile.last().second
                    else -> {
                        val (from, to) = profile.zipWithNext().first { (_, to) -> longitude <= to.first }
                        from.second + (longitude - from.first) / (to.first - from.first) * (to.second - from.second)
                    }
                }
            }

            return Terrain(positions.toList(), lookup)
        }

        private fun position() = Position(
            timestamp = time,
            latitude = LATITUDE,
            longitude = longitude,
            accuracy = 5.0,
            bearing = 90.0,
            bearingAccuracy = null,
            batteryLevel = null,
            batteryCharging = null,
        )
    }

    private companion object {
        const val FIX_INTERVAL_SECONDS = 10L
        const val LATITUDE = 52.4
        val METERS_PER_DEGREE_LONGITUDE = 6_371_000.0 * Math.PI / 180 * cos(Math.toRadians(LATITUDE))
    }
}
