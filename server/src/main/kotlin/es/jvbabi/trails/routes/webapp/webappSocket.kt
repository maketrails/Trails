package es.jvbabi.trails.routes.webapp

import es.jvbabi.trails.api.TRAILS_WEBAPP_REALM
import es.jvbabi.trails.api.TrailsWebappPrincipal
import es.jvbabi.trails.data.DeviceRepository
import es.jvbabi.trails.data.ReverseGeocoding
import es.jvbabi.trails.data.ShareRepository
import es.jvbabi.trails.data.TrackRepository
import es.jvbabi.trails.data.UserRepository
import es.jvbabi.trails.data.event.DeviceEvent
import es.jvbabi.trails.data.event.UserEvent
import es.jvbabi.trails.data.model.DeviceModel
import es.jvbabi.trails.data.model.SnapshotModel
import es.jvbabi.trails.data.model.forShare
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
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * Everything the web app shows on its start page: the user's own devices, the
 * shares they saved, and the shares they emitted — as one message, re-sent whenever
 * any of it changes.
 *
 * The whole list rather than deltas, because that is what the page renders; the
 * subscriptions below only decide *when* to send it. A device's own stream reports
 * new positions, the account's stream reports which things exist at all.
 */
fun Route.webappSocket() {

    val userRepository by inject<UserRepository>()
    val deviceRepository by inject<DeviceRepository>()
    val trackRepository by inject<TrackRepository>()
    val shareRepository by inject<ShareRepository>()
    val reverseGeocoding by inject<ReverseGeocoding>()

    authenticate(TRAILS_WEBAPP_REALM) {
        webSocket {
            val user = call.principal<TrailsWebappPrincipal>()!!.user

            // One collector job per subscribed device stream.
            val deviceSubscriptions = mutableMapOf<Uuid, Job>()

            // Reverse-geocode the last known location after the repositories have
            // answered, so the network call never happens while a database connection
            // is held.
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

            suspend fun sendDevices() {
                // Read once, so every device in this list is described as of the same
                // moment instead of each asking on its own.
                val onlineDeviceIds = deviceRepository.onlineDevices()
                val devices = deviceRepository.listOwnedBy(user.id).map { device ->
                    WebAppSocketServerMessage.DevicesUpdate.Device.of(
                        device = device,
                        snapshot = trackRepository.latestSnapshot(device.id),
                        isOnline = device.id in onlineDeviceIds,
                    )
                }

                // A saved share is stored as an active-share id on a homeserver. Only
                // the ones living here can be resolved; the rest are handed to the
                // client as a reference it fetches from their origin itself.
                val saved = shareRepository.listSavedBy(user.id)
                val shares = saved.mapNotNull { savedShare ->
                    val shared = shareRepository.getSharedDevice(savedShare.activeShareId) ?: return@mapNotNull null
                    // Read once: the position and the charge level are the same
                    // reading, and the share decides whether the latter comes with it.
                    val snapshot = trackRepository.latestSnapshot(shared.device.id)?.forShare(shared.share)
                    WebAppSocketServerMessage.DevicesUpdate.Share(
                        isOnline = shared.device.id in onlineDeviceIds,
                        id = shared.activeShare.id,
                        name = shared.share.shareName,
                        manufacturer = shared.device.manufacturer,
                        model = shared.device.model,
                        deviceFriendlyName = shared.device.friendlyName,
                        ownerUsername = shared.ownerUsername,
                        battery = snapshot?.battery?.let {
                            WebAppSocketServerMessage.DevicesUpdate.Device.Battery(it.percentage, it.isCharging)
                        },
                        lastLocation = snapshot?.let {
                            WebAppSocketServerMessage.DevicesUpdate.Device.LastLocation(
                                latitude = it.latitude,
                                longitude = it.longitude,
                                foundAt = it.createdAt.toEpochMilliseconds(),
                            )
                        },
                    )
                }
                val resolvedIds = shares.map { it.id }.toSet()
                val foreignShares = saved
                    .filter { it.activeShareId !in resolvedIds }
                    .map {
                        WebAppSocketServerMessage.DevicesUpdate.ForeignShare(
                            activeShareId = it.activeShareId,
                            homeserver = it.homeserver,
                        )
                    }

                val emittedShares = shareRepository.listEmittedBy(user.id)
                    .mapNotNull { share ->
                        val device = deviceRepository.getById(share.deviceId) ?: return@mapNotNull null
                        if (device.isDeleted) return@mapNotNull null

                        val redemptions = shareRepository.listRedemptionsOf(share.id)
                        WebAppSocketServerMessage.DevicesUpdate.EmittedShare(
                            id = share.id,
                            name = share.shareName,
                            deviceId = device.id,
                            deviceDisplayName = device.displayName,
                            manufacturer = device.manufacturer,
                            model = device.model,
                            locationHistorySeconds = share.locationHistorySeconds,
                            shareBatteryState = share.shareBatteryState,
                            allowMultiuse = share.allowMultiuse,
                            isLocked = share.isLocked,
                            createdAt = share.createdAt.toEpochMilliseconds(),
                            redemptionCount = redemptions.size.toLong(),
                            activeShares = redemptions.map {
                                WebAppSocketServerMessage.DevicesUpdate.EmittedShare.ActiveShareEntry(
                                    id = it.id,
                                    createdAt = it.createdAt.toEpochMilliseconds(),
                                )
                            },
                        )
                    }

                sendSerialized<WebAppSocketServerMessage>(
                    WebAppSocketServerMessage.DevicesUpdate(
                        devices = devices.map { it.copy(lastLocation = enrichLocation(it.lastLocation)) },
                        shares = shares.map { it.copy(lastLocation = enrichLocation(it.lastLocation)) },
                        emittedShares = emittedShares,
                        foreignShares = foreignShares,
                    )
                )
            }

            /**
             * Follow one device and re-send the list whenever what is shown about it
             * changes.
             *
             * Presence counts as a change like a position does — for shared devices too,
             * since a share hands out whether its device is reachable.
             */
            fun subscribeToDevice(deviceId: Uuid) {
                if (deviceSubscriptions[deviceId]?.isActive == true) return
                deviceSubscriptions[deviceId] = launch {
                    deviceRepository.events(deviceId)
                        .filter { it is DeviceEvent.SnapshotAdded || it is DeviceEvent.OnlineStateChanged }
                        .onEach { sendDevices() }
                        .collect()
                }
            }

            // Send the current state right after connecting.
            sendDevices()

            // Own devices, and the devices behind the saved shares: a position of
            // either changes what this page draws.
            deviceRepository.listOwnedBy(user.id).forEach { subscribeToDevice(it.id) }
            shareRepository.listSavedBy(user.id).forEach { savedShare ->
                val shared = shareRepository.getSharedDevice(savedShare.activeShareId) ?: return@forEach
                subscribeToDevice(shared.device.id)
            }

            // Re-send the whole list whenever *which* things exist changes, and keep
            // the per-device subscriptions in step with it.
            launch {
                userRepository.events(user.id)
                    .onEach { event ->
                        when (event) {
                            is UserEvent.DeviceChanged -> subscribeToDevice(event.device.id)
                            is UserEvent.DeviceRemoved ->
                                deviceSubscriptions.remove(event.deletion.deviceId)?.cancel()
                            // A share saved just now brings a device with it that
                            // nothing is following yet — without this its positions and
                            // its presence would only arrive after a reconnect.
                            is UserEvent.SavedSharesChanged -> {
                                val shared = shareRepository.getSharedDevice(event.activeShareId)
                                if (shared != null) subscribeToDevice(shared.device.id)
                            }
                            is UserEvent.EmittedSharesChanged -> {}
                            // Progress has its own socket; re-sending the device list
                            // (reverse geocoding included) for every batch would tie a
                            // progress bar to that cost.
                            is UserEvent.OptimizationProgressed -> return@onEach
                        }
                        sendDevices()
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
            /**
             * Whether the device is reachable right now. A device that is offline
             * still carries its last known position — that is precisely when it
             * matters.
             */
            @SerialName("is_online") val isOnline: Boolean,
        ) {
            companion object {
                /**
                 * [snapshot] is where the device was last seen, or null if it never
                 * reported — the position and the charge level both come from it.
                 * [isOnline] is handed in because presence is not stored with the
                 * device; only the repository knows it.
                 */
                fun of(device: DeviceModel, snapshot: SnapshotModel?, isOnline: Boolean) = Device(
                    id = device.id,
                    manufacturer = device.manufacturer,
                    model = device.model,
                    friendlyName = device.friendlyName,
                    displayName = device.displayName,
                    isOnline = isOnline,
                    battery = snapshot?.battery?.let { Battery(it.percentage, it.isCharging) },
                    lastLocation = snapshot?.let {
                        LastLocation(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            foundAt = it.createdAt.toEpochMilliseconds(),
                        )
                    },
                )
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
            /** Whether the shared device is reachable right now, see [Device.isOnline]. */
            @SerialName("is_online") val isOnline: Boolean,
        )

        /**
         * A location share this user has emitted (created) themselves. Carries
         * the share settings and how often it has been redeemed (one redemption
         * per entry). Manufacturer/model are only carried for the device image on
         * the frontend.
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
