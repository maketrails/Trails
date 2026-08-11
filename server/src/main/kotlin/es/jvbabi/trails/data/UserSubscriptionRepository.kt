package es.jvbabi.trails.data

import es.jvbabi.trails.api.TrailsAppUserPrincipal
import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.Device
import es.jvbabi.trails.database.DeviceDeletion
import es.jvbabi.trails.routes.app.AppSocketMessage
import es.jvbabi.trails.shared.dto.DeviceResponse
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketServerMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class UserSubscriptionRepository: KoinComponent {
    private val db by inject<DatabaseManager>()

    private val userSubscriptions = mutableMapOf<Uuid, MutableSharedFlow<UserSubscriptionMessage>>()
    private val userSubscriptionsMutex = Mutex()

    suspend fun getFlowForUser(userId: Uuid): MutableSharedFlow<UserSubscriptionMessage> {
        return userSubscriptionsMutex.withLock {
            userSubscriptions.getOrPut(userId) {
                MutableSharedFlow()
            }
        }
    }
}

sealed class UserSubscriptionMessage: KoinComponent {
    data class DeviceUpdated(val device: Device): UserSubscriptionMessage()
    data class DeviceDeleted(val deletion: DeviceDeletion): UserSubscriptionMessage()
    /** One of the user's saved shares was removed from the account (e.g. returned).
     * [shareId] is the active-share id. The webapp re-sends its list; the app is
     * told to drop the local share via a [TrailsWebSocketServerMessage.ShareDeleted]. */
    data class SharesChanged(val shareId: Uuid): UserSubscriptionMessage()
    /** A share this user emitted changed: its settings were edited, or it was
     * redeemed / given back. [shareId] is the share id (not an active-share id).
     * Only the webapp needs this — it re-sends its emitted-share list so the
     * settings and the redemption list stay live. */
    data class EmittedSharesChanged(val shareId: Uuid): UserSubscriptionMessage()
    data class RingState(
        val deviceId: Uuid,
        val isRinging: Boolean,
        val ringedByDeviceName: String,
    ): UserSubscriptionMessage()
    /** How far the [es.jvbabi.trails.data.TrailOptimizer] of one of the user's
     * own devices has got. Sent while a run progresses and once when it ends, so
     * the device detail view can follow along. Only the webapp shows it. */
    data class OptimizationProgress(
        val deviceId: Uuid,
        val progress: Double,
        val isRunning: Boolean,
    ): UserSubscriptionMessage()

    private val db by inject<DatabaseManager>()
    suspend fun toAppSocketMessage(
        principal: TrailsAppUserPrincipal,
    ): AppSocketMessage? {
        when (this) {
            is DeviceUpdated -> {
                if (db.transaction { principal.user.id.value != device.owner.id.value }) return null
                return AppSocketMessage(TrailsWebSocketServerMessage.DeviceUpdated(
                    data = DeviceResponse(
                        id = device.id.value.toString(),
                        manufacturer = device.manufacturer,
                        model = device.model,
                        friendlyName = device.friendlyName,
                        displayName = device.displayName,
                        ownerId = db.transaction { device.owner.id.value.toString() },
                    )
                ))
            }
            is DeviceDeleted -> {
                if (db.transaction { principal.user.id.value != deletion.device.owner.id.value }) return null
                return AppSocketMessage(TrailsWebSocketServerMessage.DeviceDeleted(
                    deletedByDeviceName = db.transaction { deletion.deletedBy?.device?.displayName ?: "Browser" },
                    deviceId = db.transaction { deletion.device.id.value.toString() },
                ))
            }
            is SharesChanged -> return AppSocketMessage(
                TrailsWebSocketServerMessage.ShareDeleted(
                    wasDeviceRemoved = false,
                    shareId = shareId.toString(),
                )
            )
            // Emitted shares are not part of the app's socket state — it reads them
            // via `GET /me/emitted-shares` when it needs them.
            is EmittedSharesChanged -> return null
            // The app draws no tracks, so optimization progress means nothing to it.
            is OptimizationProgress -> return null
            is RingState -> {
                return AppSocketMessage(TrailsWebSocketServerMessage.RingState(
                    deviceId = this.deviceId.toString(),
                    isRinging = this.isRinging,
                    ringedByDeviceName = this.ringedByDeviceName,
                ))
            }
        }
    }
}