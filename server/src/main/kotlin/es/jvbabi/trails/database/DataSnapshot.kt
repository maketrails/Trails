package database

import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class DataSnapshot(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<DataSnapshot>(DataSnapshots)

    var device by Device referencedOn DataSnapshots.device
    var createdAt by DataSnapshots.createdAt
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
    }
}