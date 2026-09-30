package es.jvbabi.trails

import es.jvbabi.trails.data.TrackPipeline
import es.jvbabi.trails.data.TrackPipeline.Position
import es.jvbabi.trails.data.model.Movement
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How the optimizer tells walking, cycling and travelling apart, on tracks made up
 * the way a phone records them: a fix every ten seconds while it moves, and none at
 * all while it stands still.
 *
 * Every track goes through [TrackPipeline.process] and a [TrackPipeline.MovementStream]
 * and is stored the way the optimizer stores it, so what is asserted is what ends up in
 * the `movements` table.
 */
class MovementClassificationTest {

    private val walking = Movement.Type.Walking
    private val bike = Movement.Type.Bike
    private val travel = Movement.Type.Travel()

    @Test
    fun `walking pace is walking`() {
        val track = track { move(kmh = 5.0, duration = 10.minutes) }

        assertEquals(listOf(walking), typesOf(track))
    }

    @Test
    fun `a bike ride is a bike ride`() {
        val track = track { move(kmh = 22.0, duration = 10.minutes) }

        assertEquals(listOf(bike), typesOf(track))
    }

    @Test
    fun `a train is travel, with the vehicle unknown`() {
        val track = track { move(kmh = 120.0, duration = 10.minutes) }

        assertEquals(listOf(Movement.Type.Travel(Movement.Vehicle.Unknown)), typesOf(track))
    }

    @Test
    fun `jitter around a spot is no movement at all`() {
        val track = track { move(kmh = 0.6, duration = 2.minutes) }

        assertEquals(emptyList(), typesOf(track))
    }

    @Test
    fun `a short walk inside a bike ride takes the ride's mode`() {
        val track = track {
            move(kmh = 22.0, duration = 6.minutes)
            pause(40.seconds)
            move(kmh = 5.0, duration = 2.minutes)
            pause(40.seconds)
            move(kmh = 22.0, duration = 6.minutes)
        }

        val movements = movementsOf(track)

        assertEquals(listOf(bike), movements.map { it.type })
        assertEquals(track.first().timestamp, movements.single().start)
        assertEquals(track.last().timestamp, movements.single().end)
    }

    @Test
    fun `a walk long enough stands between two bike rides`() {
        val track = track {
            move(kmh = 22.0, duration = 6.minutes)
            pause(40.seconds)
            move(kmh = 5.0, duration = 6.minutes)
            pause(40.seconds)
            move(kmh = 22.0, duration = 6.minutes)
        }

        assertEquals(listOf(bike, walking, bike), typesOf(track))
    }

    @Test
    fun `a train crawling between two stations at bike speed stays travel`() {
        val track = track {
            move(kmh = 60.0, duration = 8.minutes)
            pause(40.seconds)
            move(kmh = 20.0, duration = 3.minutes)
            pause(40.seconds)
            move(kmh = 60.0, duration = 8.minutes)
        }

        assertEquals(listOf(travel), typesOf(track))
    }

    @Test
    fun `a bike ride long enough stands between two train rides`() {
        val track = track {
            move(kmh = 60.0, duration = 8.minutes)
            pause(90.seconds)
            move(kmh = 20.0, duration = 8.minutes)
            pause(90.seconds)
            move(kmh = 60.0, duration = 8.minutes)
        }

        assertEquals(listOf(travel, bike, travel), typesOf(track))
    }

    @Test
    fun `getting off a train and onto a bike is told apart without a long stop`() {
        // What the phone records: no fixes while the train stands and while the bike
        // is unlocked, so the only pauses are single long, slow steps.
        val track = track {
            move(kmh = 100.0, duration = 10.minutes)
            pause(54.seconds)
            move(kmh = 5.0, duration = 6.minutes)
            pause(50.seconds)
            move(kmh = 22.0, duration = 11.minutes)
        }

        assertEquals(listOf(travel, walking, bike), typesOf(track))
    }

    @Test
    fun `stop and go at bike speed is a bus`() {
        val track = track {
            repeat(10) {
                move(kmh = 10.0, duration = 10.seconds)
                move(kmh = 25.0, duration = 40.seconds)
                move(kmh = 10.0, duration = 10.seconds)
                move(kmh = 3.0, duration = 20.seconds)
            }
        }

        assertEquals(listOf(travel), typesOf(track))
    }

    @Test
    fun `a bike ride with a few traffic lights stays a bike ride`() {
        val track = track {
            repeat(3) {
                move(kmh = 22.0, duration = 3.minutes)
                move(kmh = 8.0, duration = 20.seconds)
            }
            move(kmh = 22.0, duration = 3.minutes)
        }

        assertEquals(listOf(bike), typesOf(track))
    }

    @Test
    fun `a long pause ends a trip, and a short walk alone keeps its mode`() {
        val track = track {
            move(kmh = 5.0, duration = 2.minutes)
            pause(10.minutes)
            move(kmh = 22.0, duration = 6.minutes)
        }

        assertEquals(listOf(walking, bike), typesOf(track))
    }

    @Test
    fun `a stray fix before a recording gap does not become travel`() {
        // Standing still, one fix 220 m off in 21 s, then nothing for 38 minutes, then
        // the phone is back where it was.
        val spot = track { move(kmh = 0.6, duration = 1.minutes) }
        val last = spot.last()
        val stray = last.copy(
            timestamp = last.timestamp + 21.seconds,
            longitude = last.longitude - 220 / METERS_PER_DEGREE_LONGITUDE,
        )
        val back = last.copy(timestamp = stray.timestamp + 38.minutes)

        val track = spot + stray + back + track(start = back.timestamp, longitude = back.longitude) {
            move(kmh = 0.6, duration = 1.minutes)
        }.drop(1)

        assertEquals(emptyList(), typesOf(track))
    }

    @Test
    fun `where a batch ends does not change how a trip is judged`() {
        // The case that had it wrong: a crawl between two train rides, which only reads
        // as an interruption once both of them are there.
        val track = track {
            move(kmh = 60.0, duration = 8.minutes)
            pause(40.seconds)
            move(kmh = 20.0, duration = 3.minutes)
            pause(40.seconds)
            move(kmh = 60.0, duration = 8.minutes)
            pause(54.seconds)
            move(kmh = 5.0, duration = 6.minutes)
            pause(50.seconds)
            move(kmh = 22.0, duration = 11.minutes)
        }
        val whole = typesOf(track)
        assertEquals(listOf(travel, walking, bike), whole)

        for (batchSize in 10..track.size step 7) {
            assertEquals(whole, typesOf(track, batchSize), "batches of $batchSize")
        }
    }

    @Test
    fun `stored movements never overlap`() {
        val track = track {
            move(kmh = 22.0, duration = 6.minutes)
            pause(40.seconds)
            move(kmh = 5.0, duration = 6.minutes)
            pause(3.minutes)
            move(kmh = 60.0, duration = 8.minutes)
        }

        for (batchSize in listOf(track.size, 50, 23)) {
            val movements = movementsOf(track, batchSize)
            for ((earlier, later) in movements.zipWithNext()) {
                assertTrue(earlier.end <= later.start, "batches of $batchSize: $earlier overlaps $later")
            }
        }
    }

    // --- building tracks ---------------------------------------------------------

    /** A stored movement, as the optimizer writes it. */
    private data class Stored(val type: Movement.Type, val start: Instant, val end: Instant)

    /** What ends up stored for [positions], read in batches of [batchSize] like the optimizer does. */
    private fun movementsOf(positions: List<Position>, batchSize: Int = positions.size): List<Stored> {
        val stream = TrackPipeline.MovementStream()
        val stored = mutableListOf<List<TrackPipeline.Leg>>()

        for (batch in positions.chunked(batchSize.coerceAtLeast(1))) {
            val update = stream.add(TrackPipeline.process(batch).flatMap { it.legs })
            update.replaceFrom?.let { from -> stored.removeAll { run -> run.first().start >= from } }
            stored += update.runs
        }

        return stored.map { run -> Stored(run.first().mode, run.first().start, run.last().end) }
    }

    private fun typesOf(positions: List<Position>, batchSize: Int = positions.size) =
        movementsOf(positions, batchSize).map { it.type }

    /**
     * Records a track heading east, starting with one fix at [start]. Moving adds a fix
     * every ten seconds; a pause adds none, the way a phone that stands still reports.
     */
    private fun track(
        start: Instant = Instant.parse("2026-09-29T08:00:00Z"),
        longitude: Double = 13.1,
        build: TrackBuilder.() -> Unit,
    ): List<Position> = TrackBuilder(start, longitude).apply(build).positions

    private class TrackBuilder(start: Instant, longitude: Double) {
        private var time = start
        private var longitude = longitude
        val positions = mutableListOf(position())

        fun move(kmh: Double, duration: kotlin.time.Duration) {
            repeat((duration.inWholeSeconds / FIX_INTERVAL_SECONDS).toInt()) {
                time += FIX_INTERVAL_SECONDS.seconds
                longitude += kmh / 3.6 * FIX_INTERVAL_SECONDS / METERS_PER_DEGREE_LONGITUDE
                positions += position()
            }
        }

        fun pause(duration: kotlin.time.Duration) {
            time += duration
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
