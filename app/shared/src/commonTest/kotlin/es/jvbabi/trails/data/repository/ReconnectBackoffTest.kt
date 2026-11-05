package es.jvbabi.trails.data.repository

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReconnectBackoffTest {

    /** A [Random] that always lands in the middle of the jitter range, i.e. no jitter. */
    private object NoJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(from: Double, until: Double): Double = (from + until) / 2
    }

    private fun backoff() = ReconnectBackoff(random = NoJitter)

    private fun ReconnectBackoff.delays(count: Int, inForeground: Boolean): List<Duration> =
        List(count) { nextDelay(inForeground) }

    @Test
    fun backgroundDelaysDoubleUpToTheCap() {
        assertEquals(
            listOf(5.seconds, 10.seconds, 20.seconds, 30.seconds, 30.seconds),
            backoff().delays(5, inForeground = false),
        )
    }

    @Test
    fun foregroundDelaysDoubleUpToTheCap() {
        assertEquals(
            listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 10.seconds, 10.seconds),
            backoff().delays(6, inForeground = true),
        )
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val backoff = backoff()
        repeat(100) { backoff.nextDelay(inForeground = false) }
        assertEquals(30.seconds, backoff.nextDelay(inForeground = false))
    }

    @Test
    fun shortConnectionDoesNotResetTheBackoff() {
        val backoff = backoff()
        backoff.delays(3, inForeground = false)
        backoff.onConnectionEnded(2.seconds)
        assertEquals(30.seconds, backoff.nextDelay(inForeground = false))
    }

    @Test
    fun stableConnectionResetsTheBackoff() {
        val backoff = backoff()
        backoff.delays(3, inForeground = false)
        backoff.onConnectionEnded(5.minutes)
        assertEquals(0, backoff.failures)
        assertEquals(5.seconds, backoff.nextDelay(inForeground = false))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val backoff = ReconnectBackoff(random = Random(42))
        repeat(50) {
            backoff.reset()
            val delay = backoff.nextDelay(inForeground = false)
            assertTrue(delay in 4.seconds..6.seconds, "$delay is outside 5s ± 20%")
        }
    }
}
