package es.jvbabi.trails.domain.repository

import es.jvbabi.trails.data.database.entity.ConnectionEvent
import es.jvbabi.trails.domain.model.ActiveShare
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.shared.dto.MeResponse
import io.ktor.http.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TrailsServerRepository {

    fun connectWithHomeserver(): Deferred<Boolean>
    suspend fun connectWithOtherServer(server: String)
    suspend fun stopAllOtherServerConnections()

    fun isServerConnected(server: String): Flow<Boolean>
    val isConnected: StateFlow<Boolean>

    val isDeviceDeletedState: StateFlow<IsDeviceDeletedState>
    suspend fun resetDeviceDeletedState()

    fun getBaseUrl(): Flow<URLBuilder?>
    fun getToken(): Flow<String?>
    fun getUserId(): Flow<Uuid?>

    suspend fun checkSessionHealth(): SessionHealthState
    suspend fun getMeData(): Result<MeResponse>
    suspend fun updateUserDevices()
    suspend fun fetchDeviceImageForDevice(device: Device)

    suspend fun requestPing(device: Device): PingResult
    fun requestRing(device: Device)
    fun requestStopRing(device: Device)

    suspend fun useShareLink(hostname: String, id: String): UseShareLinkResult

    /**
     * Downloads the shares saved to the account from the homeserver and restores them
     * locally. No-op if there is no homeserver login.
     */
    suspend fun syncAccountShares()

    /**
     * Removes saved shares whose active share no longer exists on its origin
     * homeserver (returned/removed). Checks each origin directly, grouped per
     * homeserver, so it also covers shares that live only locally and never had an
     * account reference. A homeserver that can't be reached is left untouched.
     */
    suspend fun pruneRemovedShares()

    /**
     * Gives [share] back: deletes the redemption on its origin homeserver, drops the
     * account's backup reference and forgets the share locally. Client-driven
     * federation, so the origin call goes out directly. Fails without changing
     * anything if the origin can't be reached; the share's lock is deliberately not
     * lifted, so a spent link stays spent.
     */
    suspend fun returnShare(share: ActiveShare): Result<Unit>

    fun getConnectionEvents(server: String): Flow<List<ConnectionEvent>>

    suspend fun deleteDevice(device: Device): Result<Unit>

    /**
     * Renames [device]. A blank/`null` [customName] clears the custom name and
     * the server falls back to the model name.
     */
    suspend fun renameDevice(device: Device, customName: String?): Result<Unit>

    val ringStates: StateFlow<Map<Uuid, RingDeviceState>>

    /**
     * Whether each device is reachable, keyed by device id — a shared device is keyed
     * by the device behind the share, not by the redemption, so a reader that has a
     * device has the answer.
     *
     * Live state, never stored: it is fed by the server connection and starts out
     * empty, so a device missing from the map is one nothing is known about rather
     * than one that is offline.
     */
    val deviceOnlineStates: StateFlow<Map<Uuid, DeviceOnlineState>>
}

sealed class UseShareLinkResult {
    data object NotExisting : UseShareLinkResult()
    data object Used : UseShareLinkResult()
    data class Error(val message: String) : UseShareLinkResult()
    data object Success : UseShareLinkResult()
}

sealed class IsDeviceDeletedState {
    data object Unset : IsDeviceDeletedState()
    data class Deleted(val thisDevice: Device, val deletedByDeviceName: String): IsDeviceDeletedState()
}

sealed class SessionHealthState {
    data class Error(val errorMessage: String): SessionHealthState()
    data object InvalidOrExpired: SessionHealthState()
    data object Ok: SessionHealthState()
    data object NoSessionExpected: SessionHealthState()
}

sealed class PingResult {
    data class Pinged(val hasDeliveredNotification: Boolean): PingResult()
    data object Timeout: PingResult()
    data object NotAllowed: PingResult()
    data class Error(val errorMessage: String): PingResult()
}

/**
 * Whether a device is reachable, and since when.
 *
 * [since] is null when the server cannot say — it holds presence in memory, so a
 * device that has not connected since the server started is offline without a since.
 */
data class DeviceOnlineState(
    val isOnline: Boolean,
    val since: Instant?,
)

data class RingDeviceState(
    val isRinging: Boolean,
    val ringedByDeviceName: String,
)
