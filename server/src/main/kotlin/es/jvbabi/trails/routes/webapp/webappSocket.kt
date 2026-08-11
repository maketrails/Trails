package es.jvbabi.trails.routes.webapp

import database.DataSnapshot
import database.DataSnapshots
import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.DeviceSubscriptionMessage
import es.jvbabi.trails.data.DeviceSubscriptionRepository
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.UserSubscriptionMessage
import es.jvbabi.trails.data.UserSubscriptionRepository
import es.jvbabi.trails.database.ActiveShare
import es.jvbabi.trails.database.ActiveShares
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Devices
import es.jvbabi.trails.database.Share as ShareEntity
import es.jvbabi.trails.database.Shares
import es.jvbabi.trails.database.UserShare
import es.jvbabi.trails.database.UserShares
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.webappSocket() {

    val userSubscriptionRepository by inject<UserSubscriptionRepository>()
    val deviceSubscriptionRepository by inject<DeviceSubscriptionRepository>()
    val reverseGeocoding by inject<ReverseGeocoding>()
    val db by inject<DatabaseManager>()

    authenticate(TRAILS_WEBAPP_REALM) {
        webSocket {
            val user = call.principal<TrailsWebappPrincipal>()!!.user

            // One collector job per subscribed device flow.
            val deviceSubscriptions = mutableMapOf<Uuid, Job>()

            // Reverse-geocode the last known location outside the DB
            // transaction so the network call doesn't hold a connection.
            suspend fun enrichLocation(
                location: WebAppSocketServerMessage.DevicesUpdate.Device.LastLocation?,
            ): WebAppSocketServerMessage.DevicesUpdate.Device.LastLocation? {
                if (location == null) return null
                val address = reverseGeocoding.reverseGeocode(location.latitude, location.longitude)
                    ?: return location
                return location.copy(
                    address = WebAppSocketServerMessage.DevicesUpdate.Device.LastLocation.Address(
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

            // Resolve the shares this user has saved into the same Device shape.
            // A share is stored as an active-share id on a homeserver; we can only
            // resolve those that live on this server, so foreign shares are skipped.
            fun resolveShares(): List<ActiveShare> =
                UserShare.find { UserShares.user eq user.id }
                    .mapNotNull { userShare -> ActiveShare.findById(userShare.shareId) }
                    .filter { it.share.device.deletion == null }

            // Refs to saved shares that do NOT live on this server. They can't be
            // resolved locally, so the client is handed the active-share id +
            // homeserver and fetches those directly from the origin (over its own
            // per-host share socket). Anything that resolved locally is excluded.
            fun resolveForeignShares(): List<WebAppSocketServerMessage.DevicesUpdate.ForeignShare> {
                val localShareIds = resolveShares().map { it.id.value }.toSet()
                return UserShare.find { UserShares.user eq user.id }
                    .filter { it.shareId !in localShareIds }
                    .map { WebAppSocketServerMessage.DevicesUpdate.ForeignShare(activeShareId = it.shareId, homeserver = it.homeserver) }
            }

            // Resolve the shares this user has emitted (created) themselves. A
            // share is owned via its device, so we select all shares whose device
            // belongs to the current user and whose device is not deleted.
            fun resolveEmittedShares(): List<ShareEntity> =
                ShareEntity.wrapRows(
                    Shares
                        .innerJoin(Devices)
                        .select(Shares.columns)
                        .where { Devices.owner eq user.id }
                ).filter { it.device.deletion == null }

            suspend fun sendDevices() {
                val (devices, shares, emittedShares) = db.transaction {
                    val devices = user.devices
                        .filter { it.deletion == null }
                        .map { device -> WebAppSocketServerMessage.DevicesUpdate.Device.fromDevice(device) }
                    val shares = resolveShares()
                        .map { activeShare -> WebAppSocketServerMessage.DevicesUpdate.Share.fromActiveShare(activeShare) }
                    val emittedShares = resolveEmittedShares()
                        .map { share -> WebAppSocketServerMessage.DevicesUpdate.EmittedShare.fromShare(share) }
                    Triple(devices, shares, emittedShares)
                }
                val foreignShares = db.transaction { resolveForeignShares() }
                sendSerialized<WebAppSocketServerMessage>(
                    WebAppSocketServerMessage.DevicesUpdate(
                        devices = devices.map { it.copy(lastLocation = enrichLocation(it.lastLocation)) },
                        shares = shares.map { it.copy(lastLocation = enrichLocation(it.lastLocation)) },
                        emittedShares = emittedShares,
                        foreignShares = foreignShares,
                    )
                )
            }

            // Subscribe to a single device flow and re-send the device list on
            // every snapshot (location / battery change).
            fun subscribeToDevice(deviceId: Uuid) {
                if (deviceSubscriptions[deviceId]?.isActive == true) return
                deviceSubscriptions[deviceId] = launch {
                    deviceSubscriptionRepository.getFlowForDeviceSubscription(deviceId)
                        .filter { it is DeviceSubscriptionMessage.Snapshot }
                        .onEach { sendDevices() }
                        .collect()
                }
            }

            // Send the current state right after connecting.
            sendDevices()

            // Subscribe to all currently owned devices.
            db.transaction { user.devices.filter { it.deletion == null }.map { it.id.value } }
                .forEach { subscribeToDevice(it) }

            // Subscribe to the underlying devices of all saved shares so their
            // location / battery changes re-send the list too.
            db.transaction { resolveShares().map { it.share.device.id.value } }
                .forEach { subscribeToDevice(it) }

            // Re-send the full device list whenever a device of this user is
            // added, changed or removed, and keep the device subscriptions in sync.
            launch {
                userSubscriptionRepository.getFlowForUser(user.id.value)
                    .filter {
                        it is UserSubscriptionMessage.DeviceUpdated ||
                                it is UserSubscriptionMessage.DeviceDeleted ||
                                it is UserSubscriptionMessage.SharesChanged ||
                                it is UserSubscriptionMessage.EmittedSharesChanged
                    }
                    .onEach { message ->
                        when (message) {
                            is UserSubscriptionMessage.DeviceUpdated ->
                                subscribeToDevice(db.transaction { message.device.id.value })
                            is UserSubscriptionMessage.DeviceDeleted -> {
                                val deviceId = db.transaction { message.deletion.device.id.value }
                                deviceSubscriptions.remove(deviceId)?.cancel()
                            }
                            else -> {}
                        }
                        sendDevices()
                    }
                    .collect()
            }

            // Follow the optimizer of the user's own devices. Sent as its own
            // message rather than through sendDevices(): it arrives once per
            // batch, and re-sending every device (with reverse geocoding) for a
            // progress bar would be absurd.
            launch {
                userSubscriptionRepository.getFlowForUser(user.id.value)
                    .filter { it is UserSubscriptionMessage.OptimizationProgress }
                    .onEach { message ->
                        val progress = message as UserSubscriptionMessage.OptimizationProgress
                        sendSerialized<WebAppSocketServerMessage>(
                            WebAppSocketServerMessage.OptimizationProgress(
                                deviceId = progress.deviceId,
                                progress = progress.progress,
                                isRunning = progress.isRunning,
                            )
                        )
                    }
                    .collect()
            }

            // Keep the connection open until the client disconnects.
            for (frame in incoming) {
                // The webapp socket is server-push only; ignore inbound frames.
            }
        }
    }
}

@Serializable
sealed class WebAppSocketServerMessage {
    /**
     * How far the track optimization of one of the user's own devices has got.
     * Only ever sent for own devices — a share carries a track, not the state of
     * the machinery behind it.
     */
    @SerialName("optimization.progress")
    @Serializable
    data class OptimizationProgress(
        @SerialName("device_id") val deviceId: Uuid,
        /** Share of the settled positions that are optimized, 0..1. */
        @SerialName("progress") val progress: Double,
        @SerialName("is_running") val isRunning: Boolean,
    ): WebAppSocketServerMessage()

    @SerialName("devices.update")
    @Serializable
    data class DevicesUpdate(
        @SerialName("devices") val devices: List<Device>,
        @SerialName("shares") val shares: List<Share> = emptyList(),
        @SerialName("emitted_shares") val emittedShares: List<EmittedShare> = emptyList(),
        @SerialName("foreign_shares") val foreignShares: List<ForeignShare> = emptyList(),
    ): WebAppSocketServerMessage() {
        /**
         * A saved share living on another homeserver. Only the capability
         * (active-share id) and its origin are sent; the client fetches the data
         * directly from that homeserver over its per-host share socket.
         */
        @Serializable
        data class ForeignShare(
            @SerialName("active_share_id") val activeShareId: Uuid,
            @SerialName("homeserver") val homeserver: String,
        )

        @Serializable
        data class Device(
            @SerialName("id") val id: Uuid,
            @SerialName("manufacturer") val manufacturer: String,
            @SerialName("model") val model: String,
            @SerialName("friendly_name") val friendlyName: String,
            @SerialName("display_name") val displayName: String,
            @SerialName("battery") val battery: Battery?,
            @SerialName("last_location") val lastLocation: LastLocation?,
        ) {
            companion object {
                fun fromDevice(device: es.jvbabi.trails.database.Device): Device {
                    val latestSnapshot = DataSnapshot
                        .find { DataSnapshots.device eq device.id }
                        .orderBy(DataSnapshots.createdAt to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                    return Device(
                        id = device.id.value,
                        manufacturer = device.manufacturer,
                        model = device.model,
                        friendlyName = device.friendlyName,
                        displayName = device.displayName,
                        battery = latestSnapshot?.let { snapshot ->
                            val level = snapshot.batteryLevel ?: return@let null
                            val charging = snapshot.batteryCharging ?: return@let null
                            Battery(
                                percentage = (level * 100).toInt(),
                                isCharging = charging
                            )
                        },
                        lastLocation = latestSnapshot?.let { snapshot ->
                            val latitude = snapshot.latitude
                            val longitude = snapshot.longitude
                            val foundAt = snapshot.createdAt.toEpochMilliseconds()
                            LastLocation(
                                latitude = latitude,
                                longitude = longitude,
                                foundAt = foundAt
                            )
                        }
                    )
                }
            }

            @Serializable
            data class Battery(
                @SerialName("percentage") val percentage: Int,
                @SerialName("is_charging") val isCharging: Boolean
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
         * A location share this user has saved. Distinct from a [Device]: it has
         * its own share name (not a device's manufacturer/model naming) and is
         * keyed by the active-share id. Battery is only present when the share
         * allows it. Manufacturer/model are only carried for the device image.
         */
        @Serializable
        data class Share(
            @SerialName("id") val id: Uuid,
            @SerialName("name") val name: String,
            @SerialName("manufacturer") val manufacturer: String,
            @SerialName("model") val model: String,
            @SerialName("device_friendly_name") val deviceFriendlyName: String,
            @SerialName("owner_username") val ownerUsername: String,
            @SerialName("battery") val battery: Device.Battery?,
            @SerialName("last_location") val lastLocation: Device.LastLocation?,
        ) {
            companion object {
                fun fromActiveShare(activeShare: ActiveShare): Share {
                    val share = activeShare.share
                    val sourceDevice = share.device
                    val device = Device.fromDevice(sourceDevice)
                    return Share(
                        id = activeShare.id.value,
                        name = share.shareName,
                        manufacturer = device.manufacturer,
                        model = device.model,
                        deviceFriendlyName = sourceDevice.friendlyName,
                        ownerUsername = sourceDevice.owner.username,
                        battery = if (share.shareBatteryState) device.battery else null,
                        lastLocation = device.lastLocation,
                    )
                }
            }
        }

        /**
         * A location share this user has emitted (created) themselves. Carries
         * the share settings and how often it has been redeemed (one active-share
         * row per redemption). Manufacturer/model are only carried for the device
         * image on the frontend.
         */
        @Serializable
        data class EmittedShare(
            @SerialName("id") val id: Uuid,
            @SerialName("name") val name: String,
            @SerialName("device_id") val deviceId: Uuid,
            @SerialName("device_display_name") val deviceDisplayName: String,
            @SerialName("manufacturer") val manufacturer: String,
            @SerialName("model") val model: String,
            @SerialName("location_history_seconds") val locationHistorySeconds: Int,
            @SerialName("share_battery_state") val shareBatteryState: Boolean,
            @SerialName("allow_multiuse") val allowMultiuse: Boolean,
            @SerialName("is_locked") val isLocked: Boolean,
            @SerialName("created_at") val createdAt: Long,
            @SerialName("redemption_count") val redemptionCount: Long,
            @SerialName("active_shares") val activeShares: List<ActiveShareEntry> = emptyList(),
        ) {
            companion object {
                fun fromShare(share: es.jvbabi.trails.database.Share): EmittedShare {
                    val device = share.device
                    // Newest redemption first — the detail page lists them in that order.
                    val activeShares = ActiveShare
                        .find { ActiveShares.share eq share.id }
                        .orderBy(ActiveShares.createdAt to SortOrder.DESC)
                        .map { activeShare ->
                            ActiveShareEntry(
                                id = activeShare.id.value,
                                createdAt = activeShare.createdAt.toEpochMilliseconds(),
                            )
                        }
                    return EmittedShare(
                        id = share.id.value,
                        name = share.shareName,
                        deviceId = device.id.value,
                        deviceDisplayName = device.displayName,
                        manufacturer = device.manufacturer,
                        model = device.model,
                        locationHistorySeconds = share.locationHistorySeconds,
                        shareBatteryState = share.shareBatteryState,
                        allowMultiuse = share.allowMultiuse,
                        isLocked = share.isLocked,
                        createdAt = share.createdAt.toEpochMilliseconds(),
                        redemptionCount = activeShares.size.toLong(),
                        activeShares = activeShares,
                    )
                }
            }

            /**
             * One redemption of an emitted share. There is nothing to say about
             * *who* redeemed it — a share is a capability, and the redeemer may
             * live on a foreign homeserver — so only the redemption itself
             * (its id and when it happened) is carried.
             */
            @Serializable
            data class ActiveShareEntry(
                @SerialName("id") val id: Uuid,
                @SerialName("created_at") val createdAt: Long,
            )
        }
    }
}
