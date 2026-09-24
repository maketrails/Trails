@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.trails.page.devices.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.trails.domain.model.ActiveShare
import es.jvbabi.trails.domain.model.User
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.domain.usecase.home.GetHomeDeviceLocationsUseCase
import es.jvbabi.trails.page.home.HomeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.getString
import trails.app.shared.generated.resources.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How long a ring or stop request may go unconfirmed by the device before the UI gives up. */
private val RING_CONFIRMATION_TIMEOUT = 10.seconds

class DeviceViewModel(
    private val devicesRepository: DevicesRepository,
    private val getHomeDeviceLocationsUseCase: GetHomeDeviceLocationsUseCase,
    private val keyValueRepository: KeyValueRepository,
    private val userRepository: UserRepository,
    private val fileRepository: FileRepository,
    private val uiRepository: UiRepository,
    private val trailsServerRepository: TrailsServerRepository,
    private val shareRepository: ShareRepository,
): ViewModel() {

    val state: StateFlow<DeviceState>
        field = MutableStateFlow(DeviceState())

    private val deviceId = MutableStateFlow<Uuid?>(null)

    fun init(deviceId: Uuid) {
        this.deviceId.value = deviceId
    }

    init {
        viewModelScope.launch {
            deviceId
                .filterNotNull()
                .flatMapLatest { deviceId -> devicesRepository.getDeviceById(deviceId) }
                .filterNotNull()
                .flatMapLatest { device -> getHomeDeviceLocationsUseCase.getHomeDevice(device) }
                .collectLatest { device ->
                    state.update { it.copy(
                        device = device,
                        deletionState = null,
                        renameState = if (it.device?.device?.id != device.device.id) null else it.renameState,
                        returnState = if (it.device?.device?.id != device.device.id) null else it.returnState,
                        image = if (it.device?.device?.id != device.device.id) null else it.image,
                        pingState = if (it.device?.device?.id != device.device.id) null else it.pingState,
                        ringState = if (it.device?.device?.id != device.device.id) null else it.ringState,
                    ) }

                    devicesRepository.hasDeviceImage(device.device).first { it }
                    state.value = state.value.copy(
                        image = fileRepository.readFile(devicesRepository.getFileNameForDeviceImage(device.device))
                    )
                }
        }

        viewModelScope.launch {
            keyValueRepository.get(Key.UserId)
                .filterNotNull()
                .flatMapLatest { userRepository.getUser(it) }
                .collectLatest { user ->
                    state.update { it.copy(currentUser = user) }
                }
        }

        viewModelScope.launch {
            deviceId
                .filterNotNull()
                .flatMapLatest { shareRepository.getSharesForDevice(it) }
                .collectLatest { shares ->
                    state.update { it.copy(shares = shares) }
                }
        }

        viewModelScope.launch {
            var previousDeviceId: Uuid? = null
            state
                .filter { it.device != null && it.currentUser != null }
                .distinctUntilChangedBy { listOf(it.device!!.device.id, it.currentUser!!.id).sumOf { it.hashCode() } }
                .collect { snapshot ->
                    val isOwnDevice = snapshot.device!!.device.owner.id == snapshot.currentUser!!.id
                    val hasDeviceChanged = previousDeviceId != snapshot.device.device.id
                    previousDeviceId = snapshot.device.device.id

                    state.update { it.copy(
                        pingState = when {
                            hasDeviceChanged && isOwnDevice -> DeviceState.PingState.Ready
                            !isOwnDevice -> DeviceState.PingState.Disabled
                            else -> it.pingState
                        },
                        ringState = when {
                            hasDeviceChanged && isOwnDevice -> {
                                val isRinging = trailsServerRepository.ringStates.value[snapshot.device.device.id]?.isRinging == true
                                if (isRinging) DeviceState.RingState.Ringing else DeviceState.RingState.Ready
                            }
                            !isOwnDevice -> DeviceState.RingState.Disabled
                            else -> it.ringState
                        }
                    ) }
                }
        }

        viewModelScope.launch {
            trailsServerRepository.ringStates
                .combine(deviceId.filterNotNull()) { states, id ->
                    states[id]
                }
                // The map changes whenever any device rings; only this one's state matters.
                .distinctUntilChanged()
                .collectLatest { deviceRingState ->
                    ringConfirmationTimeout?.cancel()
                    state.update { it.copy(
                        ringState = when {
                            it.ringState == null || it.ringState == DeviceState.RingState.Disabled -> it.ringState
                            deviceRingState?.isRinging == true -> DeviceState.RingState.Ringing
                            else -> DeviceState.RingState.Ready
                        }
                    ) }
                }
        }
    }

    /** Falls back to the confirmed ring state when the device never answers a ring request. */
    private var ringConfirmationTimeout: Job? = null

    /**
     * Shows [DeviceState.RingState.Loading] until the device confirms the request through
     * [TrailsServerRepository.ringStates]. The device is the source of truth, so a request
     * it never confirms (offline, asleep) must not leave the button spinning forever.
     */
    private fun awaitRingConfirmation() {
        state.update { it.copy(ringState = DeviceState.RingState.Loading) }
        ringConfirmationTimeout?.cancel()
        ringConfirmationTimeout = viewModelScope.launch {
            delay(RING_CONFIRMATION_TIMEOUT)
            val isRinging = trailsServerRepository.ringStates.value[deviceId.value]?.isRinging == true
            state.update { it.copy(
                ringState = if (isRinging) DeviceState.RingState.Ringing else DeviceState.RingState.Ready
            ) }
            if (!isRinging) uiRepository.sendSnackbar(getString(Res.string.device_ring_timeout), autoDismiss = 5.seconds)
        }
    }

    fun onEvent(event: DeviceEvent) {
        when (event) {
            is DeviceEvent.Rename -> viewModelScope.launch {
                if (state.value.device == null) return@launch
                if (state.value.renameState is DeviceState.RenameState.Loading) return@launch
                state.update { it.copy(renameState = DeviceState.RenameState.Loading) }
                try {
                    val result = trailsServerRepository.renameDevice(state.value.device!!.device, event.customName)
                    if (result.isSuccess) state.update { it.copy(renameState = DeviceState.RenameState.Success) }
                    else state.update { it.copy(renameState = DeviceState.RenameState.Error(result.exceptionOrNull()?.message ?: getString(Res.string.common_unknown_error))) }
                } catch (e: Exception) {
                    // A cancellation of this coroutine goes through; anything else is a failed request.
                    ensureActive()
                    state.update { it.copy(renameState = DeviceState.RenameState.Error(e.message ?: getString(Res.string.common_unknown_error))) }
                }
            }

            is DeviceEvent.Delete -> viewModelScope.launch {
                if (state.value.device == null) return@launch
                if (state.value.deletionState is DeviceState.DeletionState.Loading) return@launch
                state.update { it.copy(deletionState = DeviceState.DeletionState.Loading) }
                try {
                    val result = trailsServerRepository.deleteDevice(state.value.device!!.device)
                    if (result.isSuccess) state.update { it.copy(deletionState = DeviceState.DeletionState.Success) }
                    else if (result.isFailure) state.update { it.copy(deletionState = DeviceState.DeletionState.Error(result.exceptionOrNull()?.message ?: getString(Res.string.common_unknown_error))) }
                } catch (e: Exception) {
                    // A cancellation of this coroutine goes through; anything else is a failed request.
                    ensureActive()
                    state.update { it.copy(deletionState = DeviceState.DeletionState.Error(e.message ?: getString(Res.string.common_unknown_error))) }
                }
            }

            is DeviceEvent.ReturnShare -> viewModelScope.launch {
                val shares = state.value.shares
                if (shares.isEmpty()) return@launch
                if (state.value.returnState is DeviceState.ReturnState.Loading) return@launch
                state.update { it.copy(returnState = DeviceState.ReturnState.Loading) }
                try {
                    // Every share of this device goes back at once: each one grants the
                    // same access, so keeping one would leave the device visible after
                    // the user returned "the" share.
                    val failure = shares
                        .map { trailsServerRepository.returnShare(it) }
                        .firstNotNullOfOrNull { it.exceptionOrNull() }
                    if (failure == null) state.update { it.copy(returnState = DeviceState.ReturnState.Success) }
                    else state.update { it.copy(returnState = DeviceState.ReturnState.Error(failure.message ?: getString(Res.string.common_unknown_error))) }
                } catch (e: Exception) {
                    // A cancellation of this coroutine goes through; anything else is a failed request.
                    ensureActive()
                    state.update { it.copy(returnState = DeviceState.ReturnState.Error(e.message ?: getString(Res.string.common_unknown_error))) }
                }
            }

            is DeviceEvent.Ping -> {
                viewModelScope.launch {
                    state.update { it.copy(pingState = DeviceState.PingState.Loading) }
                    // Every failure comes back as a PingResult, so there is nothing to catch here.
                    when (val result = trailsServerRepository.requestPing(state.value.device!!.device)) {
                        is PingResult.Pinged -> {
                            uiRepository.sendSnackbar(getString(when (result.hasDeliveredNotification) {
                                true -> Res.string.device_ping_found
                                false -> Res.string.device_ping_no_notification
                            }), autoDismiss = 5.seconds)
                            state.update { it.copy(pingState = DeviceState.PingState.Ready) }
                        }
                        PingResult.NotAllowed -> {
                            uiRepository.sendSnackbar(getString(Res.string.device_ping_not_allowed), autoDismiss = 5.seconds)
                            state.update { it.copy(pingState = DeviceState.PingState.Disabled) }
                        }
                        PingResult.Timeout -> {
                            uiRepository.sendSnackbar(getString(Res.string.device_ping_timeout), autoDismiss = 5.seconds)
                            state.update { it.copy(pingState = DeviceState.PingState.Ready) }
                        }
                        is PingResult.Error -> {
                            uiRepository.sendSnackbar(getString(Res.string.common_error_with_message, result.errorMessage), autoDismiss = 5.seconds)
                            state.update { it.copy(pingState = DeviceState.PingState.Ready) }
                        }
                    }
                }
            }

            is DeviceEvent.Ring -> {
                awaitRingConfirmation()
                trailsServerRepository.requestRing(state.value.device!!.device)
            }

            is DeviceEvent.StopRing -> {
                awaitRingConfirmation()
                trailsServerRepository.requestStopRing(state.value.device!!.device)
            }
        }
    }
}

data class DeviceState(
    val device: HomeState.HomeDevice? = null,
    val currentUser: User? = null,
    val pingState: PingState? = null,
    val ringState: RingState? = null,
    /** The redeemed shares this device is visible through; empty for an own device. */
    val shares: List<ActiveShare> = emptyList(),

    val deletionState: DeletionState? = null,
    val renameState: RenameState? = null,
    val returnState: ReturnState? = null,
    /**
     * Compared by reference on purpose: the image is only ever replaced by a freshly read
     * array, never changed in place, so comparing its contents on every update would be waste.
     */
    @Suppress("ArrayInDataClass")
    val image: ByteArray? = null,
) {
    sealed class DeletionState {
        data object Loading: DeletionState()
        data object Success: DeletionState()
        data class Error(val message: String): DeletionState()
    }

    sealed class ReturnState {
        data object Loading: ReturnState()
        data object Success: ReturnState()
        data class Error(val message: String): ReturnState()
    }

    sealed class RenameState {
        data object Loading: RenameState()
        data object Success: RenameState()
        data class Error(val message: String): RenameState()
    }

    sealed class PingState {
        data object Disabled: PingState()
        data object Loading: PingState()
        data object Ready: PingState()
    }

    sealed class RingState {
        data object Disabled: RingState()
        data object Loading: RingState()
        data object Ready: RingState()
        data object Ringing: RingState()
    }
}

sealed class DeviceEvent {
    data class Rename(val customName: String?): DeviceEvent()
    data object Delete: DeviceEvent()
    data object ReturnShare: DeviceEvent()
    data object Ping: DeviceEvent()
    data object Ring: DeviceEvent()
    data object StopRing: DeviceEvent()
}
