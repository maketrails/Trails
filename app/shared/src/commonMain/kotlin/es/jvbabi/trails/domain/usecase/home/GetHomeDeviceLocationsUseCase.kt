@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.trails.domain.usecase.home

import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.page.home.HomeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class GetHomeDeviceLocationsUseCase(
    private val devicesRepository: DevicesRepository,
    private val shareRepository: ShareRepository,
    private val snapshotRepository: SnapshotRepository,
    private val userRepository: UserRepository,
    private val fileRepository: FileRepository,
    private val keyValueRepository: KeyValueRepository,
    private val trailsServerRepository: TrailsServerRepository,
) {
    operator fun invoke(): Flow<List<HomeState.HomeDevice>> {
        return keyValueRepository.get(Key.UserId)
            .flatMapLatest { it?.let { id -> userRepository.getUser(id) } ?: flowOf(null) }
            .distinctUntilChangedBy { it?.id }
            .flatMapLatest { user ->
                val ownedDevices = user?.let { devicesRepository.getDevices(user) } ?: flowOf(emptyList())
                val sharedDevices = shareRepository.getShares()
                    .map { shares -> shares.map { it.device } }

                combine(ownedDevices, sharedDevices) { owned, shared ->
                    (owned + shared).distinctBy { it.id }
                }.distinctUntilChangedBy { list -> list.map { it.id } }
                    .flatMapLatest { devices ->
                    if (devices.isEmpty()) return@flatMapLatest flowOf(emptyList())

                    combine(devices.map { device ->
                        getHomeDevice(device)
                    }) { homeDevices ->
                        homeDevices.toList()
                    }
                }
            }
    }

    fun getHomeDevice(device: Device): Flow<HomeState.HomeDevice> {
        val snapshotFlow = snapshotRepository.getCurrentSnapshotForDevice(device)

        val imageFlow = devicesRepository.hasDeviceImage(device)
            .map { hasImage ->
                if (hasImage) {
                    fileRepository.readFile(devicesRepository.getFileNameForDeviceImage(device))
                } else {
                    null
                }
            }
            .distinctUntilChanged()

        // Presence rides along as a third source rather than being looked up where it
        // is drawn: it changes on its own schedule, and everything showing a device
        // should re-render when it does.
        val onlineStateFlow = trailsServerRepository.deviceOnlineStates
            .map { states -> states[device.id] }
            .distinctUntilChanged()

        return combine(snapshotFlow, imageFlow, onlineStateFlow) { snapshot, image, onlineState ->
            HomeState.HomeDevice(
                device = device,
                image = image,
                snapshot = snapshot,
                onlineState = onlineState,
            )
        }
    }
}
