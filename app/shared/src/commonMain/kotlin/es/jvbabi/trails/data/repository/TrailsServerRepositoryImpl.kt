package es.jvbabi.trails.data.repository

import co.touchlab.kermit.Logger
import es.jvbabi.trails.Optional
import es.jvbabi.trails.api.v1.devices.UpdateDeviceRequest
import es.jvbabi.trails.api.v1.me.RegisterUserShareRequest
import es.jvbabi.trails.api.v1.share.RedeemShareResponse
import es.jvbabi.trails.data.database.TrailsDatabase
import es.jvbabi.trails.data.database.entity.ConnectionEvent
import es.jvbabi.trails.data.database.entity.DbActiveShare
import es.jvbabi.trails.data.database.entity.DbConnectionEvent
import es.jvbabi.trails.data.database.entity.DbDevice
import es.jvbabi.trails.data.database.entity.DbUser
import es.jvbabi.trails.data.remote.ApiException
import es.jvbabi.trails.data.remote.TrailsApi
import es.jvbabi.trails.domain.model.ActiveShare
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.model.Snapshot
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.shared.dto.DeviceResponse
import es.jvbabi.trails.shared.dto.MeResponse
import es.jvbabi.trails.shared.dto.SessionHealthResponse
import es.jvbabi.trails.shared.dto.websocket.PingSource
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketAppMessage
import es.jvbabi.trails.shared.dto.websocket.TrailsWebSocketServerMessage
import es.jvbabi.trails.utils.NetworkRequestUnsuccessfulException
import es.jvbabi.trails.utils.backgroundExceptionHandler
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import trails.app.shared.generated.resources.Res
import trails.app.shared.generated.resources.notification_ping_by_browser
import trails.app.shared.generated.resources.notification_ping_by_device
import trails.app.shared.generated.resources.notification_ping_title
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TrailsServerRepositoryImpl(
    private val database: TrailsDatabase,
    private val httpClient: HttpClient,
    private val keyValueRepository: KeyValueRepository,
    private val snapshotRepository: SnapshotRepository,
    private val devicesRepository: DevicesRepository,
    private val deviceRepository: DeviceRepository,
    private val shareRepository: ShareRepository,
    private val applicationRepository: ApplicationRepository,
    private val userRepository: UserRepository,
    private val fileRepository: FileRepository,
    private val notificationRepository: NotificationRepository,
    private val trailsApi: TrailsApi,
) : TrailsServerRepository {

    val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + backgroundExceptionHandler("TrailsServerRepositoryImpl")
    )
    private val logger = Logger.withTag("TrailsServerRepositoryImpl")
    private var websocketSession: DefaultClientWebSocketSession? = null
    private val crashDetectionMarkers = mutableMapOf<String, Uuid>()
    private val crashDetectionJobs = mutableMapOf<String, Job>()
    private val homeServerSocketClient = HomeServerWebSocketClient(
        scope = scope,
        applicationRepository = applicationRepository,
        shareRepository = shareRepository,
        snapshotRepository = snapshotRepository,
        devicesRepository = devicesRepository,
        keyValueRepository = keyValueRepository,
        userRepository = userRepository,
        deviceRepository = deviceRepository,
        notificationRepository = notificationRepository,
        trailsServerRepositoryImpl = this,
        database = database,
        logger = logger,
    )
    private val externalServerSocketClient = ExternalServerWebSocketClient(
        scope = scope,
        applicationRepository = applicationRepository,
        shareRepository = shareRepository,
        snapshotRepository = snapshotRepository,
        devicesRepository = devicesRepository,
        keyValueRepository = keyValueRepository,
        deviceRepository = deviceRepository,
        userRepository = userRepository,
        notificationRepository = notificationRepository,
        trailsServerRepositoryImpl = this,
        database = database,
        logger = logger,
    )

    override val isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val isDeviceDeletedState: StateFlow<IsDeviceDeletedState>
        field = MutableStateFlow<IsDeviceDeletedState>(IsDeviceDeletedState.Unset)

    override val ringStates: StateFlow<Map<Uuid, RingDeviceState>>
        field = MutableStateFlow<Map<Uuid, RingDeviceState>>(emptyMap())

    val pendingPingResults = mutableMapOf<Uuid, CompletableDeferred<PingResult>>()

    fun updateRingState(deviceId: Uuid, state: RingDeviceState) {
        ringStates.value = ringStates.value + (deviceId to state)
    }

    override val deviceOnlineStates: StateFlow<Map<Uuid, DeviceOnlineState>>
        field = MutableStateFlow<Map<Uuid, DeviceOnlineState>>(emptyMap())

    fun updateOnlineState(deviceId: Uuid, state: DeviceOnlineState) {
        deviceOnlineStates.value = deviceOnlineStates.value + (deviceId to state)
    }

    fun removeRingState(deviceId: Uuid) {
        ringStates.value = ringStates.value - deviceId
    }

    fun setDeviceDeletedState(state: IsDeviceDeletedState) {
        isDeviceDeletedState.value = state
    }

    override suspend fun resetDeviceDeletedState() {
        val deletedState = isDeviceDeletedState.value
        if (deletedState is IsDeviceDeletedState.Deleted) {
            database.deviceDao.deleteDevicesByIds(listOf(deletedState.thisDevice.id))
            keyValueRepository.delete(Key.ThisDeviceId)
            keyValueRepository.delete(Key.UserId)
            keyValueRepository.delete(Key.Host)
            keyValueRepository.delete(Key.Token)
        }
        isDeviceDeletedState.value = IsDeviceDeletedState.Unset
    }

    override fun isServerConnected(server: String): Flow<Boolean> = combine(
        isConnected,
        getBaseUrl().map { it?.host }.distinctUntilChanged()
    ) { homeConnected, homeHost ->
        homeHost == server && homeConnected || activeExternalSessions[server]?.isActive == true
    }.distinctUntilChanged()

    private var homeServerConnectJob: Job? = null

    /**
     * Waits up to [delayMs] ms, but wakes up immediately once the app (re-)enters the
     * foreground. Returns true when the foreground change was what woke it up — the caller
     * should then start a new connection attempt right away and reset the backoff.
     */
    private suspend fun delayOrUntilForeground(delayMs: Long): Boolean {
        return withTimeoutOrNull(delayMs.milliseconds) {
            applicationRepository.getApplicationForegroundState()
                .dropWhile { it }   // skip the current foreground state
                .first { it }       // wait for the switch to foreground
            true
        } ?: false
    }

    override fun connectWithHomeserver(): Deferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        if (this.isConnected.value || homeServerConnectJob?.isActive == true) {
            deferred.complete(this.isConnected.value)
            return deferred
        }

        homeServerConnectJob = scope.launch {
            var currentRetry = 0
            while (isActive) {
                var wasConnected = false
                var locationUpdater: Job? = null
                var backlogUploader: Job? = null
                var currentServerHost: String? = null
                try {
                    val url = this@TrailsServerRepositoryImpl.getBaseUrl().first()?.apply {
                        protocol = URLProtocol.WSS
                        appendPathSegments("api", "v1", "app", "ws")
                    } ?: throw IllegalStateException("Base URL not set")
                    currentServerHost = url.host

                    val token = keyValueRepository.get(Key.Token).first()
                        ?: throw IllegalStateException("Token not set")
                    val currentDeviceId = keyValueRepository.get(Key.ThisDeviceId).first()
                        ?: throw IllegalStateException("Current device ID not set")
                    val device = runCatching { devicesRepository.getDeviceById(currentDeviceId).first() }
                        .getOrNull() ?: throw IllegalStateException("Current device not found in database")

                    logger.i { "Connecting to WS at ${url.buildString()}" }

                    websocketSession = httpClient.webSocketSession(
                        urlString = url.buildString()
                    ) {
                        bearerAuth(token)
                    }

                    isConnected.value = true
                    wasConnected = true
                    if (!deferred.isCompleted) deferred.complete(true)

                    database.connectionEventDao.upsert(
                        ConnectionEvent(
                            id = Uuid.random(),
                            server = url.host,
                            timestamp = Clock.System.now(),
                            data = ConnectionEvent.Event.Connected
                        ).toEntity()
                    )
                    startCrashDetection(url.host)

                    locationUpdater = scope.launch {
                        snapshotRepository.getCurrentSnapshotForDevice(device)
                            .filterNotNull()
                            .distinctUntilChangedBy { location ->
                                location.copy(
                                    time = Instant.DISTANT_PAST.toLocalDateTime(
                                        TimeZone.currentSystemDefault()
                                    )
                                )
                            }
                            .takeWhile { isConnected.value }
                            .filterNot { it.isSynced }
                            .collectLatest { snapshot ->
                                val ws = websocketSession ?: return@collectLatest
                                logger.i { "Sending location update: $snapshot" }
                                ws.sendOrLog(snapshot.toDataSnapshotMessage(), logger)
                            }
                    }

                    backlogUploader = scope.launch {
                        // The server acknowledges each snapshot individually; until the
                        // acknowledgement arrives, is_synced stays 0. Without this set the next
                        // query would upload the same snapshots again.
                        val pendingSnapshotIds = mutableSetOf<Uuid>()
                        while (isActive && isConnected.value) {
                            val ws = websocketSession
                            if (ws == null) {
                                delay(SNAPSHOT_BACKLOG_IDLE_INTERVAL)
                                continue
                            }

                            val batch = snapshotRepository.getUnsyncedSnapshots(
                                deviceId = device.id,
                                olderThan = Clock.System.now() - SNAPSHOT_BACKLOG_MIN_AGE,
                                excludedIds = pendingSnapshotIds,
                                limit = SNAPSHOT_BACKLOG_BATCH_SIZE,
                            )

                            if (batch.isEmpty()) {
                                delay(SNAPSHOT_BACKLOG_IDLE_INTERVAL)
                                continue
                            }

                            logger.i { "Uploading ${batch.size} unsynced snapshots" }
                            batch.forEach { snapshot ->
                                pendingSnapshotIds += snapshot.id
                                ws.sendOrLog(snapshot.toDataSnapshotMessage(), logger)
                            }

                            delay(SNAPSHOT_BACKLOG_BATCH_INTERVAL)
                        }
                    }

                    homeServerSocketClient.run(websocketSession!!, url.host)

                    locationUpdater.cancel()
                    backlogUploader.cancel()
                    stopCrashDetection(url.host)

                    isConnected.value = false
                    websocketSession?.close()
                    websocketSession = null

                    database.connectionEventDao.upsert(ConnectionEvent(
                        id = Uuid.random(),
                        server = url.host,
                        timestamp = Clock.System.now(),
                        data = ConnectionEvent.Event.Disconnected
                    ).toEntity())

                } catch (e: Exception) {
                    Logger.e(e) { "Error connecting to WS: ${e.message}" }
                    locationUpdater?.cancel()
                    backlogUploader?.cancel()
                    if (currentServerHost != null) stopCrashDetection(currentServerHost)
                    isConnected.value = false
                    database.connectionEventDao.upsert(ConnectionEvent(
                        id = Uuid.random(),
                        server = currentServerHost ?: "unknown",
                        timestamp = Clock.System.now(),
                        data = ConnectionEvent.Event.Disconnected
                    ).toEntity())
                }

                // Number of failed attempts after which the caller is no longer blocked. The loop
                // does NOT give up, it keeps reconnecting in the background (a tracking app has to
                // reconnect permanently).
                val retriesBeforeUnblockingCaller = 30
                if (!wasConnected) {
                    if (currentRetry >= retriesBeforeUnblockingCaller && !deferred.isCompleted) {
                        deferred.complete(false)
                    }
                    val delayMs = if (applicationRepository.getApplicationForegroundState().first()) {
                        1_000L
                    } else {
                        // Avoid `1L shl` with an oversized exponent -> cap the exponent.
                        minOf(30_000L, 5_000L * (1L shl minOf(currentRetry, 6)))
                    }
                    // When the app comes to the foreground, retry immediately and reset the backoff.
                    if (delayOrUntilForeground(delayMs)) currentRetry = 0 else currentRetry++
                } else {
                    if (!deferred.isCompleted) deferred.complete(true)
                    val delayMs = if (applicationRepository.getApplicationForegroundState().first()) {
                        1_000L
                    } else {
                        5_000L
                    }
                    delay(delayMs.milliseconds)
                    currentRetry = 0
                }
            }
        }
        return deferred
    }

    override fun getBaseUrl(): Flow<URLBuilder?> {
        return keyValueRepository.get(Key.Host)
            .map {
                if (it == null) null
                else URLBuilder(it.let {
                    if (it.startsWith("https://")) it
                    else "https://$it"
                })
            }
    }

    override fun getToken(): Flow<String?> {
        return keyValueRepository.get(Key.Token)
    }

    override fun getUserId(): Flow<Uuid?> = keyValueRepository.get(Key.UserId)

    override suspend fun checkSessionHealth(): SessionHealthState {
        val token = getToken().first() ?: return SessionHealthState.NoSessionExpected
        val url = (getBaseUrl().first() ?: return SessionHealthState.NoSessionExpected).apply {
            appendPathSegments("api", "v1", "app", "session-healthcheck")
        }

        val response = httpClient.get(url.buildString()) {
            bearerAuth(token)
        }

        if (!response.status.isSuccess()) {
            return SessionHealthState.Error("Error checking session health: ${response.status} ${response.bodyAsText()}")
        }

        when (val data = response.body<SessionHealthResponse>()) {
            is SessionHealthResponse.DeviceDeleted -> {
                val thisDeviceId = keyValueRepository.get(Key.ThisDeviceId).first() ?: return SessionHealthState.NoSessionExpected
                val thisDevice = devicesRepository.getDeviceById(thisDeviceId).firstOrNull() ?: return SessionHealthState.NoSessionExpected
                isDeviceDeletedState.update { IsDeviceDeletedState.Deleted(thisDevice = thisDevice, deletedByDeviceName = data.deletedByDeviceName) }
                return SessionHealthState.InvalidOrExpired
            }
            is SessionHealthResponse.Valid -> return SessionHealthState.Ok
        }
    }

    override suspend fun getMeData(): Result<MeResponse> {
        val token = getToken().first() ?: throw IllegalStateException("Token not set")
        val url = (getBaseUrl().first() ?: throw IllegalStateException("Base URL not set")).apply {
            appendPathSegments("api", "v1", "me")
        }

        val response = httpClient.get(url.buildString()) {
            bearerAuth(token)
        }

        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.Unauthorized) {
                keyValueRepository.delete(Key.Token)
                keyValueRepository.delete(Key.UserId)
                keyValueRepository.delete(Key.ThisDeviceId)
                keyValueRepository.delete(Key.Host)

                return Result.failure(IllegalStateException("Token expired"))
            }
            return Result.failure(IllegalStateException("Error fetching me data: ${response.status} ${response.bodyAsText()}"))
        }

        val body = response.body<MeResponse>()

        database.userDao.upsert(
            DbUser(
                id = Uuid.parse(body.id),
                homeserver = url.host,
                username = body.username,
            )
        )

        keyValueRepository.set(Key.UserId, Uuid.parse(body.id))
        keyValueRepository.set(Key.ThisDeviceId, Uuid.parse(body.thisDeviceId))

        return Result.success(body)
    }

    override suspend fun updateUserDevices() {
        val token = getToken().first() ?: throw IllegalStateException("Token not set")
        val userId = getUserId().first() ?: throw IllegalStateException("User ID not set")
        val user = userRepository.getUser(userId).firstOrNull() ?: throw IllegalStateException("User not found in database")
        val url = (getBaseUrl().first() ?: throw IllegalStateException("Base URL not set")).apply {
            appendPathSegments("api", "v1", "devices")
        }

        val response = httpClient.get(url.buildString()) {
            bearerAuth(token)
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Error fetching devices: ${response.status}")
        }

        val body = response.body<List<DeviceResponse>>()

        body
            .map { DbDevice(
                id = Uuid.parse(it.id),
                manufacturer = it.manufacturer,
                model = it.model,
                friendlyName = it.friendlyName,
                displayName = it.displayName,
                ownerId = userId,
            ) }
            .let { database.deviceDao.upsertDevices(it) }

        devicesRepository.getDevices().first()
            .filterNot { devicesRepository.hasDeviceImage(it).first() }
            .forEach { fetchDeviceImageForDevice(it) }

        val ownDevices = devicesRepository.getDevices(user).first()
        devicesRepository.removeDevices(ownDevices.filter { device -> body.none { it.id == device.id.toString() } })
    }

    override suspend fun fetchDeviceImageForDevice(device: Device) {
        val url = URLBuilder("https://${device.owner.homeserver}").apply {
            appendPathSegments("api", "v1", "devices", "image", "${device.manufacturer}-${device.model}")
        }

        val response = httpClient.get(url.buildString())
        if (!response.status.isSuccess()) {
            logger.w { "Device image not found for device ${device.id} at ${url.buildString()}" }
            return
        }
        val sink = fileRepository.getFileSink(devicesRepository.getFileNameForDeviceImage(device))
        response.bodyAsChannel().copyAndClose(sink.asByteWriteChannel())
    }

    override suspend fun requestPing(device: Device): PingResult {
        val deferred = CompletableDeferred<PingResult>()
        pendingPingResults[device.id] = deferred
        val session = websocketSession
        if (session == null || !session.isActive) return PingResult.Error("WebSocket not connected")
        try {
            session.sendSerialized<TrailsWebSocketAppMessage>(TrailsWebSocketAppMessage.DevicePing(device.id.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The socket can die between the isActive check above and the write.
            pendingPingResults.remove(device.id)
            logger.w(e) { "Failed to send ping for device ${device.id}: ${e.message}" }
            return PingResult.Error(e.message ?: "Failed to send ping")
        }
        val result = withTimeoutOrNull(10.seconds) { deferred.await() }
        pendingPingResults.remove(device.id)
        return result ?: PingResult.Timeout
    }

    override fun requestRing(device: Device) {
        scope.launch {
            val session = websocketSession ?: return@launch
            session.sendOrLog(TrailsWebSocketAppMessage.DeviceRing(device.id.toString()), logger)
        }
    }

    override fun requestStopRing(device: Device) {
        scope.launch {
            val session = websocketSession ?: return@launch
            session.sendOrLog(TrailsWebSocketAppMessage.DeviceRingStop(device.id.toString()), logger)
        }
    }

    override suspend fun useShareLink(hostname: String, id: String): UseShareLinkResult {
        val redeemUrl = URLBuilder("https://$hostname").apply {
            appendPathSegments("api", "v1", "share", id, "redeem")
        }.buildString()

        val response = httpClient.post(redeemUrl)
        if (response.status == HttpStatusCode.NotFound) return UseShareLinkResult.NotExisting
        if (!response.status.isSuccess() && response.status != HttpStatusCode.Forbidden) {
            Logger.e(NetworkRequestUnsuccessfulException(response)) { "Error using share link" }
            return UseShareLinkResult.Error("Error using share link: ${response.status}")
        }

        val activeShareId = when (val body = response.body<RedeemShareResponse>()) {
            RedeemShareResponse.ShareLocked -> return UseShareLinkResult.Used
            is RedeemShareResponse.Success -> body.activeShareId
        }

        return try {
            resolveAndStoreActiveShare(hostname, activeShareId)
            // Back up the share to our own account if we are signed in.
            registerActiveShareWithAccount(originHomeserver = hostname, activeShareId = activeShareId)
            UseShareLinkResult.Success
        } catch (e: ApiException) {
            if (e.statusCode == HttpStatusCode.NotFound.value) {
                UseShareLinkResult.NotExisting
            } else {
                Logger.e(e) { "Error resolving share entities" }
                UseShareLinkResult.Error("Error using share link: ${e.statusCode}")
            }
        }
    }

    /**
     * Resolves an active share on [hostname] via the chain ActiveShare -> Share -> Device ->
     * Owner and stores the user, device and active share locally.
     */
    private suspend fun resolveAndStoreActiveShare(hostname: String, activeShareId: Uuid) {
        val activeShare = trailsApi.getActiveShare(hostname, activeShareId)
        val share = trailsApi.getShare(hostname, activeShare.shareId)
        val device = trailsApi.getDevice(hostname, share.deviceId)
        val owner = trailsApi.getUser(hostname, device.ownerId)

        database.userDao.upsert(DbUser(
            id = owner.id,
            homeserver = hostname,
            username = owner.username,
        ))

        database.deviceDao.upsertDevices(listOf(
            DbDevice(
                id = device.id,
                manufacturer = device.manufacturer,
                model = device.model,
                friendlyName = device.friendlyName,
                displayName = device.displayName,
                ownerId = owner.id,
            )
        ))

        val localDevice = devicesRepository.getDeviceById(device.id).first()
            ?: throw IllegalStateException("Device not found after using share link")
        if (!devicesRepository.hasDeviceImage(localDevice).first()) {
            fetchDeviceImageForDevice(localDevice)
        }

        database.activeShareDao.upsert(DbActiveShare(
            id = activeShareId,
            deviceId = device.id,
        ))
    }

    /**
     * Registers a redeemed active share with the account on our homeserver so it can be
     * restored on app start. Best-effort: if it fails (or we are not signed in), the local
     * redeem still remains valid.
     */
    private suspend fun registerActiveShareWithAccount(originHomeserver: String, activeShareId: Uuid) {
        val token = getToken().first() ?: return
        val accountHost = getBaseUrl().first()?.host ?: return
        runCatching {
            trailsApi.registerUserShare(
                host = accountHost,
                token = token,
                request = RegisterUserShareRequest(shareId = activeShareId, homeserver = originHomeserver),
            )
        }.onFailure { Logger.w(it) { "Failed to register share with account" } }
    }

    override suspend fun syncAccountShares() {
        val token = getToken().first() ?: return
        val accountHost = getBaseUrl().first()?.host ?: return

        val shares = runCatching { trailsApi.getUserShares(accountHost, token) }
            .getOrElse {
                Logger.w(it) { "Failed to download account shares" }
                return
            }

        shares.forEach { entry ->
            runCatching { resolveAndStoreActiveShare(entry.homeserver, entry.shareId) }
                .onFailure { Logger.w(it) { "Failed to restore share ${entry.shareId}" } }
        }
    }

    override suspend fun pruneRemovedShares() {
        val localShares = database.activeShareDao.getActiveShares().first()
        if (localShares.isEmpty()) return

        localShares
            .groupBy { it.device.owner.homeserver }
            .forEach { (homeserver, shares) ->
                val host = homeserver.ifBlank { getBaseUrl().first()?.host } ?: return@forEach
                val ids = shares.map { it.share.id }

                // A homeserver we can't reach is skipped, so a transient failure
                // never deletes shares that may still be valid.
                val existing = runCatching { trailsApi.bulkCheckActiveShares(host, ids) }
                    .getOrElse {
                        Logger.w(it) { "Failed to check shares on $host" }
                        return@forEach
                    }
                    .toSet()

                (ids - existing).forEach { goneId ->
                    Logger.i { "Pruning returned share $goneId (gone from $host)" }
                    database.activeShareDao.deleteById(goneId)
                }
            }
    }

    override suspend fun returnShare(share: ActiveShare): Result<Unit> {
        // The stored homeserver is the one the share was registered with, so it both
        // addresses the origin and identifies the account reference. Blank means the
        // share came from our own homeserver.
        val originHomeserver = share.device.owner.homeserver
        val accountHost = getBaseUrl().first()?.host
        val originHost = originHomeserver.ifBlank { accountHost }
            ?: return Result.failure(IllegalStateException("No homeserver known for share ${share.id}"))

        // Deleting the redemption is the step that actually revokes our access, so a
        // failure here aborts: nothing is forgotten locally and the user can retry.
        runCatching { trailsApi.returnActiveShare(originHost, share.id) }
            .onFailure {
                Logger.e(it) { "Failed to return share ${share.id} on $originHost" }
                return Result.failure(it)
            }

        // The account only holds a backup reference. Losing this call leaves a stale
        // entry that points at a share that no longer exists, which syncAccountShares
        // and pruneRemovedShares already tolerate — so it must not fail the return.
        val token = getToken().first()
        if (token != null && accountHost != null) {
            runCatching { trailsApi.deleteUserShare(accountHost, token, share.id, originHomeserver) }
                .onFailure { Logger.w(it) { "Failed to remove account reference for share ${share.id}" } }
        }

        database.activeShareDao.deleteById(share.id)
        return Result.success(Unit)
    }

    typealias ServerHost = String
    private val activeExternalSessions = mutableMapOf<ServerHost, DefaultClientWebSocketSession>()

    override suspend fun connectWithOtherServer(server: String) = connectWithOtherServer(server, 0)

    private suspend fun connectWithOtherServer(server: String, retryCount: Int) {
        var currentRetry = retryCount
        while (currentCoroutineContext().isActive) {
            if (activeExternalSessions[server]?.isActive == true) return
            val url = URLBuilder("wss://$server").apply {
                appendPathSegments("api", "v1", "app", "ws")
            }

            var wasConnected = false
            try {
                Logger.i { "Connecting with external server $server" }
                activeExternalSessions[server] = httpClient.webSocketSession(urlString = url.buildString())

                database.connectionEventDao.upsert(ConnectionEvent(
                    id = Uuid.random(),
                    server = url.host,
                    timestamp = Clock.System.now(),
                    data = ConnectionEvent.Event.Connected,
                ).toEntity())
                startCrashDetection(server)
                wasConnected = true

                externalServerSocketClient.run(activeExternalSessions[server]!!, server)
                stopCrashDetection(server)

                database.connectionEventDao.upsert(ConnectionEvent(
                    id = Uuid.random(),
                    server = url.host,
                    timestamp = Clock.System.now(),
                    data = ConnectionEvent.Event.Disconnected
                ).toEntity())

                activeExternalSessions[server]?.close()
                activeExternalSessions.remove(server)

            } catch (e: Exception) {
                Logger.e(e) { "Error connecting to WS: ${e.message}" }
                stopCrashDetection(server)
                database.connectionEventDao.upsert(ConnectionEvent(
                    id = Uuid.random(),
                    server = url.host,
                    timestamp = Clock.System.now(),
                    data = ConnectionEvent.Event.Disconnected
                ).toEntity())
            }

            if (!wasConnected) {
                // Never give up for good, keep retrying with a capped backoff.
                val delayMs = if (applicationRepository.getApplicationForegroundState().first()) {
                    1_000L
                } else {
                    minOf(30_000L, 5_000L * (1L shl minOf(currentRetry, 6)))
                }
                // When the app comes to the foreground, retry immediately and reset the backoff.
                if (delayOrUntilForeground(delayMs)) currentRetry = 0 else currentRetry++
            } else {
                val delayMs = if (applicationRepository.getApplicationForegroundState().first()) {
                    1_000L
                } else {
                    5_000L
                }
                delay(delayMs.milliseconds)
                currentRetry = 0
            }
        }
    }

    override suspend fun stopAllOtherServerConnections() {
        activeExternalSessions.map {
            scope.launch { it.value.close(); activeExternalSessions.remove(it.key) }
        }.joinAll()
    }

    private suspend fun startCrashDetection(server: String) {
        val markerId = Uuid.random()
        crashDetectionMarkers[server] = markerId
        database.connectionEventDao.upsert(
            ConnectionEvent(
                id = markerId,
                server = server,
                timestamp = Clock.System.now() + 2.seconds,
                data = ConnectionEvent.Event.Disconnected,
            ).toEntity()
        )
        crashDetectionJobs[server] = scope.launch {
            while (isActive) {
                delay(1.seconds)
                database.connectionEventDao.upsert(
                    ConnectionEvent(
                        id = markerId,
                        server = server,
                        timestamp = Clock.System.now() + 2.seconds,
                        data = ConnectionEvent.Event.Disconnected,
                    ).toEntity()
                )
            }
        }
    }

    private fun stopCrashDetection(server: String) {
        crashDetectionJobs[server]?.cancel()
        crashDetectionJobs.remove(server)
        crashDetectionMarkers.remove(server)?.let { markerId ->
            scope.launch { database.connectionEventDao.delete(markerId) }
        }
    }

    override fun getConnectionEvents(server: String): Flow<List<ConnectionEvent>> {
        return database.connectionEventDao.getEvents(server).map { events ->
            val connectionEvents = events.map(DbConnectionEvent::toModel)
            val now = Clock.System.now()
            val latestDisconnect = connectionEvents.firstOrNull { it.data is ConnectionEvent.Event.Disconnected }
            if (latestDisconnect != null && latestDisconnect.timestamp - now > 0.seconds) {
                connectionEvents.filterNot { it.id == latestDisconnect.id }
            } else connectionEvents
        }
    }

    override suspend fun deleteDevice(device: Device): Result<Unit> {
        val url = URLBuilder("https://${device.owner.homeserver}").apply {
            appendPathSegments("api", "v1", "devices", device.id.toString())
        }
        val token = getToken().first() ?: throw IllegalStateException("Token not set")

        val response = httpClient.delete(url.buildString()) {
            bearerAuth(token)
        }

        if (response.status.isSuccess()) {
            database.deviceDao.deleteDevicesByIds(listOf(device.id))
            return Result.success(Unit)
        }

        return Result.failure(IllegalStateException("Error deleting device: ${response.status} ${response.bodyAsText()}"))
    }

    override suspend fun renameDevice(device: Device, customName: String?): Result<Unit> {
        val url = URLBuilder("https://${device.owner.homeserver}").apply {
            appendPathSegments("api", "v1", "devices", device.id.toString())
        }
        val token = getToken().first() ?: throw IllegalStateException("Token not set")

        // Blank names clear the custom name; the server then falls back to the
        // model name and broadcasts the change back to us via DeviceUpdated.
        val newName = customName?.trim()?.takeIf { it.isNotEmpty() }

        val response = httpClient.patch(url.buildString()) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateDeviceRequest(customName = Optional.Defined(newName)))
        }

        if (response.status.isSuccess()) return Result.success(Unit)

        return Result.failure(IllegalStateException("Error renaming device: ${response.status} ${response.bodyAsText()}"))
    }
}

/** Number of snapshots the backlog upload sends to the server per pass. */
private const val SNAPSHOT_BACKLOG_BATCH_SIZE = 50

/**
 * Minimum age of a snapshot before the backlog upload touches it. Fresh snapshots already go out
 * via the live path; the delay keeps both paths from uploading the same one.
 */
private val SNAPSHOT_BACKLOG_MIN_AGE = 10.minutes

/** Pause between two batches — time for the server's acknowledgements to arrive. */
private val SNAPSHOT_BACKLOG_BATCH_INTERVAL = 5.seconds

/** Pause while there is nothing to catch up on. */
private val SNAPSHOT_BACKLOG_IDLE_INTERVAL = 60.seconds

private fun Snapshot.toDataSnapshotMessage() = TrailsWebSocketAppMessage.DataSnapshot(
    snapshotId = id,
    latitude = location.latitude,
    longitude = location.longitude,
    bearing = location.bearing,
    bearingAccuracy = location.bearingAccuracy,
    locationAccuracy = location.locationAccuracy,
    batteryLevel = batteryState?.percentage?.div(100f),
    batteryCharging = batteryState?.isCharging,
    time = time.toInstant(TimeZone.currentSystemDefault()).epochSeconds,
)

/**
 * Sends [message] and logs — rather than propagates — a write failure.
 *
 * Use for fire-and-forget messages launched outside the connect loop's `try`. The socket can
 * die between the caller's `isActive` check and the write; the connect loop notices the dead
 * session and reconnects, so a dropped message must not escape as an uncaught exception and
 * take the process down with it.
 */
private suspend fun DefaultClientWebSocketSession.sendOrLog(
    message: TrailsWebSocketAppMessage,
    logger: Logger,
) {
    try {
        sendSerialized(message)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Dropping WS message $message: ${e.message}" }
    }
}

private abstract class WebSocketClientBase(
    protected val scope: CoroutineScope,
    protected val applicationRepository: ApplicationRepository,
    protected val shareRepository: ShareRepository,
    protected val snapshotRepository: SnapshotRepository,
    protected val devicesRepository: DevicesRepository,
    protected val deviceRepository: DeviceRepository,
    protected val trailsServerRepositoryImpl: TrailsServerRepositoryImpl,
    protected val userRepository: UserRepository,
    protected val keyValueRepository: KeyValueRepository,
    protected val notificationRepository: NotificationRepository,
    protected val database: TrailsDatabase,
    protected val logger: Logger,
) {
    suspend fun run(session: DefaultClientWebSocketSession, serverHost: String) {
        val appForegroundSyncer = startShareSubscriptionSync(serverHost) { session }
        handleIncomingMessages(session)
        appForegroundSyncer.cancel()
    }

    private fun startShareSubscriptionSync(
        serverHost: String,
        sessionProvider: () -> DefaultClientWebSocketSession?
    ) = scope.launch {
        val subscribedShares = mutableSetOf<Uuid>()
        launch {
            shareRepository.getShares()
                .map { it.filter { share -> share.device.owner.homeserver == serverHost } }
                .map { it.toSet() }
                .distinctUntilChanged()
                .collectLatest { shares ->
                    val newShareIds = shares.map { it.id }.toSet() - subscribedShares
                    sessionProvider()?.sendOrLog(
                        TrailsWebSocketAppMessage.ShareSubscribe(newShareIds.map { it.toString() }),
                        logger,
                    )
                    subscribedShares.addAll(newShareIds)

                    val removedShareIds = subscribedShares - shares.map { it.id }.toSet()
                    sessionProvider()?.sendOrLog(
                        TrailsWebSocketAppMessage.ShareUnsubscribe(removedShareIds.map { it.toString() }),
                        logger,
                    )
                    subscribedShares.removeAll(removedShareIds)
                }
        }
        launch {
            if (applicationRepository.getApplicationForegroundState().first()) {
                sessionProvider()?.sendOrLog(TrailsWebSocketAppMessage.StartRtUpdates, logger)
            }
            applicationRepository.getApplicationForegroundState().collectLatest { inForeground ->
                if (inForeground) {
                    sessionProvider()?.sendOrLog(TrailsWebSocketAppMessage.StartRtUpdates, logger)
                } else {
                    sessionProvider()?.sendOrLog(TrailsWebSocketAppMessage.StopRtUpdates, logger)
                }
            }
        }
    }

    private suspend fun handleIncomingMessages(session: DefaultClientWebSocketSession) {
        for (frame in session.incoming) {
            if (frame is Frame.Text) {
                val message = session.converter!!.deserialize<TrailsWebSocketServerMessage>(frame)
                logger.i { "Received WS message: $message" }

                when (message) {
                    is TrailsWebSocketServerMessage.ShareDeleted -> {
                        runCatching { Uuid.parse(message.shareId) }.getOrNull()?.let { database.activeShareDao.deleteById(it) }
                    }

                    is TrailsWebSocketServerMessage.Ping -> {
                        val body = when (message.pingedBySource) {
                            PingSource.BROWSER -> getString(Res.string.notification_ping_by_browser)
                            PingSource.DEVICE -> getString(Res.string.notification_ping_by_device, message.pingedByDeviceName)
                        }
                        val notificationSent = notificationRepository.sendNotification(
                            channelId = NotificationRepository.PING_CHANNEL_ID,
                            title = getString(Res.string.notification_ping_title),
                            body = body,
                            notificationId = message.pingedByDeviceName.hashCode()
                        )
                        session.sendSerialized<TrailsWebSocketAppMessage>(TrailsWebSocketAppMessage.Pong(notificationSent))
                    }

                    is TrailsWebSocketServerMessage.Ring -> {
                        deviceRepository.startRinging(
                            causedByDeviceName = message.ringedByDeviceName,
                            onStop = { scope.launch { session.sendOrLog(TrailsWebSocketAppMessage.RingStop, logger) } }
                        )
                        session.sendSerialized<TrailsWebSocketAppMessage>(TrailsWebSocketAppMessage.RingStart)
                    }

                    is TrailsWebSocketServerMessage.RingStop -> {
                        deviceRepository.stopRinging()
                    }

                    is TrailsWebSocketServerMessage.RingState -> {
                        val deviceId = runCatching { Uuid.parse(message.deviceId) }.getOrNull() ?: continue
                        if (message.isRinging) {
                            trailsServerRepositoryImpl.updateRingState(deviceId, RingDeviceState(isRinging = true, ringedByDeviceName = message.ringedByDeviceName))
                        } else {
                            trailsServerRepositoryImpl.removeRingState(deviceId)
                        }
                    }

                    is TrailsWebSocketServerMessage.PingResult -> {
                        val targetDeviceId = runCatching { Uuid.parse(message.deviceId) }.getOrNull() ?: continue
                        val deferred = trailsServerRepositoryImpl.pendingPingResults[targetDeviceId] ?: continue
                        if (message.success) {
                            deferred.complete(PingResult.Pinged(message.hasDeliveredNotification))
                        } else {
                            deferred.complete(PingResult.Error(message.errorMessage ?: "Unknown error"))
                        }
                    }

                    is TrailsWebSocketServerMessage.DeviceUpdated -> {
                        val userId = Uuid.parse(message.data.ownerId)
                        userRepository.getUser(userId).firstOrNull() ?: continue
                        val deviceId = Uuid.parse(message.data.id)
                        database.deviceDao.upsertDevices(listOf(DbDevice(
                            id = deviceId,
                            manufacturer = message.data.manufacturer,
                            friendlyName = message.data.friendlyName,
                            displayName = message.data.displayName,
                            model = message.data.model,
                            ownerId = userId
                        )))
                    }

                    is TrailsWebSocketServerMessage.DeviceDeleted -> {
                        val deletedDeviceId = Uuid.parse(message.deviceId)
                        val thisDeviceId = keyValueRepository.get(Key.ThisDeviceId).firstOrNull()
                        if (thisDeviceId == deletedDeviceId) {
                            val thisDevice = devicesRepository.getDeviceById(thisDeviceId).firstOrNull() ?: continue
                            trailsServerRepositoryImpl.setDeviceDeletedState(IsDeviceDeletedState.Deleted(
                                thisDevice = thisDevice,
                                deletedByDeviceName = message.deletedByDeviceName,
                            ))
                        } else {
                            database.deviceDao.deleteDevicesByIds(listOf(deletedDeviceId))
                        }
                    }

                    is TrailsWebSocketServerMessage.SnapshotAcknowledged -> {
                        scope.launch {
                            val snapshot = snapshotRepository.getSnapshotById(message.snapshotId).first()
                                ?: return@launch

                            snapshotRepository.storeSnapshot(snapshot.copy(isSynced = true))
                        }
                    }

                    // Answered straight away and nothing else: it is the answer itself
                    // the server is waiting for, as proof that the app — not a proxy in
                    // between — is still on the other end.
                    is TrailsWebSocketServerMessage.Heartbeat -> {
                        session.sendSerialized<TrailsWebSocketAppMessage>(TrailsWebSocketAppMessage.HeartbeatAck)
                    }

                    is TrailsWebSocketServerMessage.OnlineState -> {
                        // Addressed like a position, and resolved the same way: a share
                        // is keyed by the device behind it, so everything that draws a
                        // device can ask with the id it already has.
                        val device = when (val target = message.target) {
                            is TrailsWebSocketServerMessage.Snapshot.Target.Device -> runCatching { Uuid.parse(target.deviceId) }.getOrNull()?.let { devicesRepository.getDeviceById(it).firstOrNull() }
                            is TrailsWebSocketServerMessage.Snapshot.Target.Share -> runCatching { Uuid.parse(target.shareId) }.getOrNull()?.let { shareRepository.getShareById(it).firstOrNull()?.device }
                        }
                        if (device == null) {
                            logger.w { "Received online state for unknown device in WS message: $message" }
                            continue
                        }
                        trailsServerRepositoryImpl.updateOnlineState(
                            deviceId = device.id,
                            state = DeviceOnlineState(
                                isOnline = message.isOnline,
                                since = message.since?.let { Instant.fromEpochMilliseconds(it) },
                            ),
                        )
                    }

                    is TrailsWebSocketServerMessage.Snapshot -> {
                        val device = when (val target = message.target) {
                            is TrailsWebSocketServerMessage.Snapshot.Target.Device -> runCatching { Uuid.parse(target.deviceId) }.getOrNull()?.let { devicesRepository.getDeviceById(it).firstOrNull() }
                            is TrailsWebSocketServerMessage.Snapshot.Target.Share -> runCatching { Uuid.parse(target.shareId) }.getOrNull()?.let { shareRepository.getShareById(it).firstOrNull()?.device }
                        }
                        if (device == null) {
                            logger.w { "Received snapshot for unknown device in WS message: $message" }
                            continue
                        }
                        val timestamp = Instant.fromEpochSeconds(message.timestamp)
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                        snapshotRepository.storeSnapshot(
                            Snapshot(
                                id = message.snapshotId,
                                device = device,
                                time = timestamp,
                                location = Location(
                                    latitude = message.location.latitude,
                                    longitude = message.location.longitude,
                                    bearing = message.location.bearing,
                                    bearingAccuracy = message.location.bearingAccuracy,
                                    locationAccuracy = message.location.locationAccuracy,
                                    time = timestamp,
                                ),
                                batteryState = message.batteryState?.let {
                                    BatteryState(
                                        percentage = it.percentage,
                                        isCharging = it.isCharging,
                                    )
                                },
                                isSynced = true,
                            )
                        )
                    }
                }
            }
        }
    }
}

private class HomeServerWebSocketClient(
    scope: CoroutineScope,
    applicationRepository: ApplicationRepository,
    shareRepository: ShareRepository,
    snapshotRepository: SnapshotRepository,
    devicesRepository: DevicesRepository,
    trailsServerRepositoryImpl: TrailsServerRepositoryImpl,
    keyValueRepository: KeyValueRepository,
    notificationRepository: NotificationRepository,
    userRepository: UserRepository,
    deviceRepository: DeviceRepository,
    database: TrailsDatabase,
    logger: Logger,
) : WebSocketClientBase(
    scope = scope,
    applicationRepository = applicationRepository,
    shareRepository = shareRepository,
    snapshotRepository = snapshotRepository,
    devicesRepository = devicesRepository,
    trailsServerRepositoryImpl = trailsServerRepositoryImpl,
    notificationRepository = notificationRepository,
    keyValueRepository = keyValueRepository,
    deviceRepository = deviceRepository,
    userRepository = userRepository,
    database = database,
    logger = logger,
)

private class ExternalServerWebSocketClient(
    scope: CoroutineScope,
    applicationRepository: ApplicationRepository,
    shareRepository: ShareRepository,
    snapshotRepository: SnapshotRepository,
    devicesRepository: DevicesRepository,
    deviceRepository: DeviceRepository,
    trailsServerRepositoryImpl: TrailsServerRepositoryImpl,
    userRepository: UserRepository,
    keyValueRepository: KeyValueRepository,
    notificationRepository: NotificationRepository,
    database: TrailsDatabase,
    logger: Logger,
) : WebSocketClientBase(
    scope = scope,
    applicationRepository = applicationRepository,
    shareRepository = shareRepository,
    snapshotRepository = snapshotRepository,
    devicesRepository = devicesRepository,
    trailsServerRepositoryImpl = trailsServerRepositoryImpl,
    userRepository = userRepository,
    deviceRepository = deviceRepository,
    keyValueRepository = keyValueRepository,
    notificationRepository = notificationRepository,
    database = database,
    logger = logger,
)
