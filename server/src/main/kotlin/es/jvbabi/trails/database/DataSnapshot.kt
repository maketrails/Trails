package database

import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

class DataSnapshot(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<DataSnapshot>(DataSnapshots)

    var device by Device referencedOn DataSnapshots.device
    var createdAt by DataSnapshots.createdAt
    var insertedAt by DataSnapshots.insertedAt
    var longitude by DataSnapshots.longitude
    var latitude by DataSnapshots.latitude
    var locationAccuracy by DataSnapshots.locationAccuracy
    var bearing by DataSnapshots.bearing
    var bearingAccuracy by DataSnapshots.bearingAccuracy
    var batteryLevel by DataSnapshots.batteryLevel
    var batteryCharging by DataSnapshots.batteryCharging
    var isRaw by DataSnapshots.isRaw
}

object DataSnapshots : UuidTable("data_snapshots") {
    val device = reference("device", Devices, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("timestamp")

    /**
     * When this row was stored, as opposed to [createdAt], which is when the position
     * was *recorded*.
     *
     * The two come apart in both directions: an app that was offline uploads
     * measurements long after they happened, and the [es.jvbabi.trails.data.TrailOptimizer]
     * derives optimized positions that carry the timestamp of the measurement they
     * came from, years after the fact and again on every rebuild. Only this column
     * grows monotonically with the writes, which is what lets a client ask for
     * "everything stored since I last looked" and get the rebuilt positions too.
     *
     * Filled in by the client default, so no insert has to remember it. Rows that
     * predate the column were backfilled with [createdAt] (see
     * `server/migrations`), which is the closest the past can be reconstructed.
     */
    val insertedAt = timestamp("inserted_at").clientDefault { Clock.System.now() }
    val longitude = double("longitude")
    val latitude = double("latitude")
    val locationAccuracy = double("location_accuracy")
    val bearing = double("bearing")
    val bearingAccuracy = double("bearing_accuracy").nullable()
    val batteryLevel = float("battery_level").nullable()
    val batteryCharging = bool("battery_charging").nullable()
    val isRaw = bool("is_raw").default(true)

    init {
        /*
         * The optimized track is derived from the raw positions and keeps their
         * timestamps, so a device can hold two snapshots for the same instant:
         * the measurement and the optimized position. `is_raw` is therefore
         * part of a snapshot's identity.
         */
        index(true, device, createdAt, isRaw)

        /*
         * Serves the incremental history reads: "everything this device stored since
         * X" stays a range scan over the tail instead of a walk across its whole
         * series, which is the entire point of handing a client a cursor.
         */
        index(false, device, insertedAt)
    }
}