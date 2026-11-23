@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.trails.page.home

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.domain.usecase.home.GetHomeDeviceLocationsUseCase
import es.jvbabi.trails.utils.IntPaddingValues
import es.jvbabi.trails.utils.toMapCamera
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.uuid.Uuid

/**
 * Drives the map surface.
 *
 * The map is rendered above the navigation display and therefore outlives every destination.
 * Its state lives here rather than in [HomeViewModel] so that the renderer, the loaded style and
 * the current camera survive navigating into another area of the app and back.
 */
class MapViewModel(
    private val keyValueRepository: KeyValueRepository,
    private val locationRepository: LocationRepository,
    private val devicesRepository: DevicesRepository,
    private val getHomeDeviceLocationsUseCase: GetHomeDeviceLocationsUseCase,
) : ViewModel() {

    val state: StateFlow<MapState>
        field = MutableStateFlow(MapState())

    private val viewportDimensions = MutableStateFlow<IntSize?>(null)
    val mapContentPadding = MutableStateFlow<IntPaddingValues?>(null)
    private val localDensity = MutableStateFlow<Float?>(null)
    private val followDevice = MutableStateFlow<Device?>(null)

    init {
        viewModelScope.launch(CoroutineName("OwnLocation")) {
            locationRepository.getCurrentLocation().collect { location ->
                state.update { it.copy(ownLocation = location) }
            }
        }

        viewModelScope.launch(CoroutineName("This device")) {
            keyValueRepository.get(Key.ThisDeviceId)
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { id -> devicesRepository.getDeviceById(id) }
                .collectLatest { device ->
                    state.update { it.copy(currentDevice = device) }
                }
        }

        viewModelScope.launch(CoroutineName("All devices")) {
            getHomeDeviceLocationsUseCase()
                .collectLatest { devices ->
                    state.update { it.copy(devices = devices) }
                }
        }

        viewModelScope.launch(CoroutineName("Update camera position")) {
            val stateFlow = state
                .distinctUntilChangedBy { stateSnapshot ->
                    listOf(
                        stateSnapshot.trackingMode,
                        stateSnapshot.currentDevice,
                        stateSnapshot.devices
                    ).sumOf { it.hashCode() }
                }
                .map {
                    object {
                        val devices = it.devices
                        val trackingMode = it.trackingMode
                        val ownLocation = it.currentDeviceLocation
                    }
                }

            val measurementsFlow =
                combine(viewportDimensions.filterNotNull(), mapContentPadding.filterNotNull()) { viewportDimensions, mapContentPadding ->
                    object {
                        val viewport = viewportDimensions
                        val contentPadding = mapContentPadding
                    }
                }

            combine(
                stateFlow,
                measurementsFlow,
                localDensity.filterNotNull(),
                followDevice,
            ) { emission, measurements, localDensity, followDevice ->

                if (followDevice != null) {
                    val bounds = calculateBounds(null, emission.devices.filter { device -> device.device.id == followDevice.id }) ?: return@combine
                    val cameraState = bounds.toMapCamera(
                        viewportWidthPx = measurements.viewport.width,
                        viewportHeightPx = measurements.viewport.height,
                        density = localDensity,
                        padding = measurements.contentPadding,
                        defaultZoom = 18.0,
                        minZoom = 0.0,
                    )
                    state.update { it.copy(targetCameraState = cameraState) }
                } else when (emission.trackingMode) {
                    MapState.TrackingMode.None -> {
                        state.update { it.copy(targetCameraState = null) }
                    }
                    MapState.TrackingMode.Overview -> {
                        // This device is among the devices already, at its Trails position.
                        val bounds = calculateBounds(null, emission.devices) ?: return@combine
                        val cameraState = bounds.toMapCamera(
                            viewportWidthPx = measurements.viewport.width,
                            viewportHeightPx = measurements.viewport.height,
                            density = localDensity,
                            padding = measurements.contentPadding,
                            defaultZoom = 18.0,
                            minZoom = 0.0,
                        )

                        state.update { it.copy(targetCameraState = cameraState) }
                    }

                    MapState.TrackingMode.OwnLocation -> {
                        if (emission.ownLocation == null) return@combine
                        state.update {
                            it.copy(targetCameraState = MapState.MapCamera(
                                centerLatitude = emission.ownLocation.latitude,
                                centerLongitude = emission.ownLocation.longitude,
                                zoom = 20.0,
                                pitch = 70.0,
                                bearing = emission.ownLocation.bearing.toDouble()
                            ))
                        }
                    }
                }
            }.collectLatest {}
        }
    }

    fun setup(localDensity: Float) {
        this.localDensity.update { localDensity }
    }

    fun onEvent(event: MapEvent) {
        when (event) {
            is MapEvent.UserDragged -> {
                state.update { it.copy(trackingMode = MapState.TrackingMode.None) }
                followDevice.update { null }
            }
            is MapEvent.ToggleTrackingMode -> state.update {
                val next = when (it.trackingMode) {
                    MapState.TrackingMode.None -> MapState.TrackingMode.Overview
                    MapState.TrackingMode.Overview -> MapState.TrackingMode.OwnLocation
                    MapState.TrackingMode.OwnLocation -> MapState.TrackingMode.Overview
                }
                it.copy(trackingMode = next)
            }

            is MapEvent.OnViewportResize -> viewportDimensions.update { event.viewportDimensions }
            is MapEvent.OnMapContentAreaPadding -> mapContentPadding.update { event.mapContentPadding }
            is MapEvent.FocusDevice -> followDevice.update { state.value.devices.firstOrNull { device -> device.device.id == event.deviceId }?.device }
        }
    }

    companion object {
        fun calculateBounds(location: Location?, devices: List<HomeState.HomeDevice>): MapState.FitBounds? {
            val coords = mutableListOf<Pair<Double, Double>>()
            location?.let { coords.add(it.latitude to it.longitude) }
            devices.forEach { device ->
                val snapshot = device.snapshot ?: return@forEach
                coords.add(snapshot.location.latitude to snapshot.location.longitude)
            }
            if (coords.isEmpty()) return null

            val distinctCoords = mutableListOf<Pair<Double, Double>>()
            for (coord in coords) {
                if (distinctCoords.none { distanceInMeters(it.first, it.second, coord.first, coord.second) < 20.0 }) {
                    distinctCoords.add(coord)
                }
            }

            if (distinctCoords.isEmpty()) return null
            return MapState.FitBounds(coordinates = distinctCoords)
        }

        private fun distanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0
            val dLat = (lat2 - lat1) * (PI / 180.0)
            val dLon = (lon2 - lon1) * (PI / 180.0)
            val a = (sin(dLat / 2) * sin(dLat / 2) +
                    cos(lat1 * (PI / 180.0)) * cos(lat2 * (PI / 180.0)) *
                    sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }
    }
}

data class MapState(
    val ownLocation: Location? = null,
    val currentDevice: Device? = null,
    val devices: List<HomeState.HomeDevice> = emptyList(),
    val targetCameraState: MapCamera? = null,
    val trackingMode: TrackingMode = TrackingMode.Overview,
) {
    /**
     * Where Trails puts this device: the location of its latest snapshot. Unlike
     * [ownLocation], the raw GPS fix, it only moves once Trails records a new position,
     * so it is what the location puck and the own-location camera follow.
     */
    val currentDeviceLocation: Location?
        get() = devices.firstOrNull { it.device.id == currentDevice?.id }?.snapshot?.location

    enum class TrackingMode {
        Overview, OwnLocation, None
    }

    data class MapCamera(
        val centerLatitude: Double,
        val centerLongitude: Double,
        val zoom: Double,
        val pitch: Double,
        val bearing: Double,
    )

    data class FitBounds(
        val coordinates: List<Pair<Double, Double>>,
    )
}

sealed class MapEvent {
    data object UserDragged : MapEvent()
    data object ToggleTrackingMode : MapEvent()

    data class OnViewportResize(val viewportDimensions: IntSize) : MapEvent()
    data class OnMapContentAreaPadding(val mapContentPadding: IntPaddingValues) : MapEvent()
    data class FocusDevice(val deviceId: Uuid?) : MapEvent()
}
