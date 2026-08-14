package es.jvbabi.trails.routes.active_share

import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.model.forShare
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A shared device's current state, keyed by an active-share id. Honours the
 * share's settings (battery is withheld unless the share opted in).
 */
@Serializable
data class ShareSnapshotResponse(
    @SerialName("name") val name: String,
    @SerialName("manufacturer") val manufacturer: String,
    @SerialName("model") val model: String,
    @SerialName("device_friendly_name") val deviceFriendlyName: String,
    @SerialName("owner_username") val ownerUsername: String,
    @SerialName("last_location") val lastLocation: LastLocation?,
    @SerialName("battery") val battery: Battery?,
    /**
     * Whether the shared device is reachable right now. Part of the position, in
     * effect: it says whether what is shown is current or the last thing known.
     */
    @SerialName("is_online") val isOnline: Boolean,
) {
    @Serializable
    data class Battery(
        @SerialName("percentage") val percentage: Int,
        @SerialName("is_charging") val isCharging: Boolean,
    )

    @Serializable
    data class LastLocation(
        @SerialName("latitude") val latitude: Double,
        @SerialName("longitude") val longitude: Double,
        @SerialName("found_at") val foundAt: Long,
        @SerialName("address") val address: Address? = null,
    ) {
        @Serializable
        data class Address(
            @SerialName("road") val road: String?,
            @SerialName("house_number") val houseNumber: String?,
            @SerialName("postcode") val postcode: String?,
            @SerialName("city") val city: String?,
            @SerialName("state") val state: String?,
            @SerialName("country") val country: String?,
            @SerialName("display_name") val displayName: String?,
            @SerialName("label") val label: String,
        )
    }
}

/**
 * Builds the current snapshot for an active share, or `null` if the share (or its
 * device) no longer exists.
 *
 * What the share may reveal is applied by [forShare], the one place that rule
 * lives. Reverse geocoding runs on what the repositories already returned, so no
 * network call happens while a database connection is held.
 */
suspend fun buildShareSnapshot(
    shareRepository: ShareRepository,
    trackRepository: TrackRepository,
    deviceRepository: DeviceRepository,
    reverseGeocoding: ReverseGeocoding,
    activeShareId: Uuid,
): ShareSnapshotResponse? {
    val shared = shareRepository.getSharedDevice(activeShareId) ?: return null
    val snapshot = trackRepository.latestSnapshot(shared.device.id)?.forShare(shared.share)

    val base = ShareSnapshotResponse(
        name = shared.share.shareName,
        manufacturer = shared.device.manufacturer,
        model = shared.device.model,
        deviceFriendlyName = shared.device.friendlyName,
        ownerUsername = shared.ownerUsername,
        lastLocation = snapshot?.let {
            ShareSnapshotResponse.LastLocation(
                latitude = it.latitude,
                longitude = it.longitude,
                foundAt = it.createdAt.toEpochMilliseconds(),
            )
        },
        battery = snapshot?.battery?.let {
            ShareSnapshotResponse.Battery(percentage = it.percentage, isCharging = it.isCharging)
        },
        isOnline = deviceRepository.isOnline(shared.device.id),
    )

    val enriched = base.lastLocation?.let { location ->
        val address = reverseGeocoding.reverseGeocode(location.latitude, location.longitude)
            ?: return@let location
        location.copy(
            address = ShareSnapshotResponse.LastLocation.Address(
                road = address.road,
                houseNumber = address.houseNumber,
                postcode = address.postcode,
                city = address.city,
                state = address.state,
                country = address.country,
                displayName = address.displayName,
                label = address.shortLabel,
            )
        )
    }

    return base.copy(lastLocation = enriched)
}
