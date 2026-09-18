package es.jvbabi.trails.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * When the optimized track of a device was last thrown away and derived again from
 * scratch — one row per device, none for a device that was never rebuilt.
 *
 * A regular optimizer run only extends the track, and a client continuing from its
 * cursor picks that up. A full rebuild can change the whole history, so anything a
 * client cached before [rebuiltAt] is stale and has to be read again in full.
 *
 * A table of its own because `SchemaUtils.create` adds missing tables, but no columns
 * to existing ones.
 */
object TrackRebuilds : Table("track_rebuild") {
    val device = reference("device", Devices, onDelete = ReferenceOption.CASCADE)
    val rebuiltAt = timestamp("rebuilt_at")

    override val primaryKey = PrimaryKey(device)
}
