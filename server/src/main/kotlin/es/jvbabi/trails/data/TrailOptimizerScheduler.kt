package es.jvbabi.trails.data

import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.isNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Keeps the optimized track of every device up to date.
 *
 * One ticker walks all devices every [INTERVAL] and holds on to a
 * [TrailOptimizer] per device, which is what makes runs non-overlapping: a
 * device that is still optimizing drops the next tick instead of collecting a
 * queue of pending runs behind it.
 */
class TrailOptimizerScheduler : KoinComponent {

    private val db by inject<DatabaseManager>()

    private val logger = LoggerFactory.getLogger("TrailOptimizer")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One optimizer per device, so its run lock spans ticks and is shared with
     * whoever triggers a run by hand. Concurrent because request handlers reach
     * it through [optimizerFor] while the ticker walks it.
     */
    private val optimizers = ConcurrentHashMap<Uuid, TrailOptimizer>()

    private var ticker: Job? = null

    companion object {
        /**
         * How often a device is looked at. Anything younger than
         * [TrailOptimizer.IGNORE_LATEST] is skipped anyway, so there is nothing
         * to gain from a tighter interval.
         */
        val INTERVAL: Duration = 5.minutes
    }

    fun start() {
        if (ticker != null) return

        logger.info("Optimizing every device every $INTERVAL")

        ticker = scope.launch {
            while (isActive) {
                // A failing pass must not kill the ticker - the next one may
                // well succeed, and a device is never worse off than unoptimized.
                runCatching { tick() }
                    .onFailure { error -> logger.warn("Optimization pass failed", error) }

                delay(INTERVAL)
            }
        }
    }

    fun close() {
        scope.cancel()
        ticker = null
    }

    /**
     * The one optimizer of a device. Going through here rather than
     * constructing one is what keeps a device's runs from overlapping.
     *
     * Has to be called inside a transaction — the optimizer reads the owner of
     * the device it is given.
     */
    fun optimizerFor(device: Device): TrailOptimizer =
        optimizers.computeIfAbsent(device.id.value) { TrailOptimizer(device) }

    /**
     * Optimizes the devices one after another. Sequential on purpose: a pass
     * runs in the background and there is no deadline, while all devices at
     * once would fight over the database.
     */
    private suspend fun tick() {
        val devices = db.transaction {
            Device
                .find { Devices.deletion.isNull() }
                .associate { device -> device.id.value to optimizerFor(device) }
        }

        // Deleted devices take their optimizer with them.
        optimizers.keys.retainAll(devices.keys)

        for ((deviceId, optimizer) in devices) {
            runCatching { optimizer.optimize() }
                .onFailure { error -> logger.warn("Optimizing device $deviceId failed", error) }
        }
    }
}
