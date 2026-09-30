package es.jvbabi.trails

import es.jvbabi.trails.data.model.Movement
import es.jvbabi.trails.data.model.MovementModel
import es.jvbabi.trails.data.model.visibleFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a share's retention window leaves of a movement: a share may only reveal what
 * happened inside it, and a movement's distance is a way to see past its edge.
 */
class MovementVisibilityTest {

    private val start = Instant.parse("2026-09-29T15:24:00Z")

    private val ride = MovementModel(
        id = Uuid.random(),
        startsAt = start,
        endsAt = start + 11.minutes,
        distanceMeters = 3_880.0,
        type = Movement.Type.Bike,
        insertedAt = start + 12.minutes,
    )

    /** Fails the test if the distance is asked for when it must not be. */
    private val noMeasuring: (Instant, Instant) -> Double = { _, _ -> error("measured without need") }

    @Test
    fun `without a window a movement is shown as it is`() {
        assertSame(ride, ride.visibleFrom(null, noMeasuring))
    }

    @Test
    fun `a movement inside the window is shown as it is`() {
        assertSame(ride, ride.visibleFrom(start - 1.minutes, noMeasuring))
        assertSame(ride, ride.visibleFrom(start, noMeasuring))
    }

    @Test
    fun `a movement that ended before the window is not shown`() {
        assertNull(ride.visibleFrom(start + 12.minutes, noMeasuring))
    }

    @Test
    fun `a movement reaching into the window starts at it and counts only what lies inside`() {
        val windowStart = start + 5.minutes
        var measured: Pair<Instant, Instant>? = null

        val visible = ride.visibleFrom(windowStart) { from, to ->
            measured = from to to
            2_100.0
        }

        assertEquals(windowStart, visible?.startsAt)
        assertEquals(ride.endsAt, visible?.endsAt)
        assertEquals(2_100.0, visible?.distanceMeters)
        assertEquals(windowStart to ride.endsAt, measured)
        assertEquals(ride.id, visible?.id)
        assertEquals(ride.type, visible?.type)
    }
}
