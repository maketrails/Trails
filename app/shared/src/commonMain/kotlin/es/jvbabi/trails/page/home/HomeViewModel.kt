package es.jvbabi.trails.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.repository.DeviceOnlineState
import es.jvbabi.trails.domain.model.Snapshot
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.domain.usecase.SetupNotificationsUseCase
import es.jvbabi.trails.page.devices.Screen
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(
    private val keyValueRepository: KeyValueRepository,
    private val backgroundServiceRepository: BackgroundServiceRepository,
    private val trailsServerRepository: TrailsServerRepository,
    private val setupNotificationsUseCase: SetupNotificationsUseCase,
) : ViewModel() {

    val state: StateFlow<HomeState>
        field = MutableStateFlow(HomeState())

    init {
        viewModelScope.launch(CoroutineName("Start service if user exists + update user data")) {
            val doesUserExist = keyValueRepository.get(Key.UserId).first() != null
            if (!doesUserExist) return@launch

            setupNotificationsUseCase()

            val sessionHealth = trailsServerRepository.checkSessionHealth()
            if (sessionHealth is SessionHealthState.InvalidOrExpired || sessionHealth is SessionHealthState.NoSessionExpected) return@launch
            if (sessionHealth is SessionHealthState.Error) {
                Logger.e { "Failed to get session health: ${sessionHealth.errorMessage}" }
                return@launch
            }

            trailsServerRepository.getMeData()
            trailsServerRepository.updateUserDevices()
            trailsServerRepository.syncAccountShares()
            trailsServerRepository.pruneRemovedShares()
            backgroundServiceRepository.startService()
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.SelectTab -> state.update { it.copy(selectedTab = event.tab) }
        }
    }
}

data class HomeState(
    val selectedTab: Tab = Tab.MyDevices(Screen.Main),
) {
    sealed class Tab {
        data class MyDevices(val initialRoute: Screen): Tab()
        data object Things: Tab()
        data object Shares: Tab()
    }

    data class HomeDevice(
        val device: Device,
        val image: ByteArray?,
        val snapshot: Snapshot?,
        /**
         * Whether the device is reachable, or null while nothing is known about it —
         * before the server connection has said anything, which is not the same as
         * being offline.
         */
        val onlineState: DeviceOnlineState? = null,
    )
}

sealed class HomeEvent {
    data class SelectTab(val tab: HomeState.Tab) : HomeEvent()
}
