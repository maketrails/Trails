package es.jvbabi.trails.data

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.database.Device
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import kotlin.time.Instant

/**
 * The recorded positions of a device the way a track should show them: the
 * optimized series as far as it reaches, then the raw measurements that come
 * after it.
 *
 * The [TrailOptimizer] only covers what has settled, so the newest positions
 * are always still raw. Handing out the optimized series alone would make a
 * track end minutes in the past; mixing both everywhere would draw every
 * stretch twice. Switching over at the last optimized position gives one
 * continuous track: clean where it can be, live at the tip.
 *
 * Must be called inside a transaction — the result are Exposed entities.
 *
 * @param since Only positions at or after this instant, for the retention
 *   window of a share. Null means the whole history.
 */
fun deviceTrack(device: Device, since: Instant? = null): List<DataSnapshot> {
    val window = since?.let { DataSnapshots.createdAt greaterEq it } ?: Op.TRUE

    val optimizedEnd = DataSnapshot
        .find { (DataSnapshots.device eq device.id) and (DataSnapshots.isRaw eq false) }
        .orderBy(DataSnapshots.createdAt to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.createdAt

    val optimized = DataSnapshot
        .find { (DataSnapshots.device eq device.id) and (DataSnapshots.isRaw eq false) and window }
        .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
        .toList()

    val raw = DataSnapshot
        .find {
            val tail = optimizedEnd?.let { DataSnapshots.createdAt greater it } ?: Op.TRUE

            (DataSnapshots.device eq device.id) and (DataSnapshots.isRaw eq true) and tail and window
        }
        .orderBy(DataSnapshots.createdAt to SortOrder.ASC)
        .toList()

    return optimized + raw
}
