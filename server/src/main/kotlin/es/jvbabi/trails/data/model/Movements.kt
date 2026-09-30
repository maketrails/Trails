package es.jvbabi.trails.data.model

import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.Devices
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

/**
 * How a device moved from [startsAt] to [endsAt] — one stretch of the same
 * [Type], derived by the [es.jvbabi.trails.data.TrailOptimizer] together with the
 * optimized track and, like it, thrown away and rebuilt with it.
 */
class Movement(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Movement>(Movements)

    var device by Device referencedOn Movements.device
    var startsAt by Movements.startsAt
    var endsAt by Movements.endsAt
    var distanceMeters by Movements.distanceMeters
    var type by Movements.type

    /** The way of moving. A sealed class rather than an enum, so a type can carry details. */
    @Serializable
    sealed class Type {
        @Serializable
        @SerialName("walking")
        data object Walking : Type()

        @Serializable
        @SerialName("bike")
        data object Bike : Type()

        /** Anything faster than a bike: a vehicle someone else drives or rails carry. */
        @Serializable
        @SerialName("travel")
        data class Travel(
            @SerialName("vehicle") val vehicle: Vehicle = Vehicle.Unknown
        ) : Type()
    }

    /** What carried the device on a [Type.Travel]. Speed alone cannot tell yet, hence [Unknown]. */
    @Serializable
    sealed class Vehicle {
        @Serializable
        @SerialName("train")
        data object Train : Vehicle()

        @Serializable
        @SerialName("car")
        data object Car : Vehicle()

        @Serializable
        @SerialName("bus")
        data object Bus : Vehicle()

        @Serializable
        @SerialName("unknown")
        data object Unknown : Vehicle()
    }
}

object Movements : UuidTable("movements") {
    /**
     * Writes defaults out: a row has to keep meaning what it meant when it was
     * written, even if a default like [Movement.Vehicle.Unknown] changes later.
     */
    private val json = Json { encodeDefaults = true }

    val device = reference("device", Devices, onDelete = ReferenceOption.CASCADE)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")

    /**
     * Along the optimized track, summed over the legs of the movement. The few metres
     * drifted during a stop between two legs are not counted.
     */
    val distanceMeters = double("distance_meters")

    /**
     * Stored as JSON, e.g. `{"type":"travel","vehicle":{"type":"unknown"}}`, so a type
     * can gain details without a schema change.
     */
    val type = text("type").transform(
        wrap = { json.decodeFromString<Movement.Type>(it) },
        unwrap = { json.encodeToString<Movement.Type>(it) }
    )

    init {
        // Serves "the movements of this device from X on", the only way they are read.
        index(false, device, startsAt)
    }
}
