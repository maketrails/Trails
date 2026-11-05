package es.jvbabi.trails.data.repository

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff for the WebSocket reconnect loops.
 *
 * Every call to [nextDelay] counts as one failed attempt and doubles the delay, up to a cap.
 * The foreground uses a shorter base and cap than the background: an open app should come back
 * quickly, a backgrounded one should not drain the battery against a server that is down.
 *
 * Only a connection that stayed up for at least [stableConnection] counts as a success and
 * resets the backoff (see [onConnectionEnded]). A connection that is accepted and dropped right
 * away is a failure, otherwise a flapping server would be hit every few seconds forever.
 */
internal class ReconnectBackoff(
    private val foregroundBase: Duration = 1.seconds,
    private val foregroundMax: Duration = 10.seconds,
    private val backgroundBase: Duration = 5.seconds,
    private val backgroundMax: Duration = 30.seconds,
    private val stableConnection: Duration = 30.seconds,
    private val random: Random = Random.Default,
) {
    /** Number of consecutive failed attempts since the last reset. */
    var failures: Int = 0
        private set

    /**
     * Records the end of an attempt that got past the handshake. A connection that lasted at
     * least [stableConnection] resets the backoff; a shorter one is left to [nextDelay] to count
     * as a failure.
     */
    fun onConnectionEnded(connectedFor: Duration) {
        if (connectedFor >= stableConnection) reset()
    }

    /** Starts over at the base delay, e.g. when the app comes to the foreground. */
    fun reset() {
        failures = 0
    }

    /**
     * Returns the delay before the next attempt and counts the current one as failed. The delay
     * is jittered by ±20% so that many devices losing the same server don't reconnect in lockstep.
     */
    fun nextDelay(inForeground: Boolean): Duration {
        val base = if (inForeground) foregroundBase else backgroundBase
        val max = if (inForeground) foregroundMax else backgroundMax
        // Cap the exponent so the shift cannot overflow; the cap on the result does the rest.
        val delay = minOf(max, base * (1 shl minOf(failures, MAX_EXPONENT)))
        failures++
        return delay * random.nextDouble(1 - JITTER, 1 + JITTER)
    }

    private companion object {
        const val MAX_EXPONENT = 10
        const val JITTER = 0.2
    }
}
