package es.jvbabi.trails.page.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.times
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.CornersOut
import com.phosphor.icons.regular.Gear
import com.phosphor.icons.regular.GpsFix
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.trails.ThemeWrapper
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.model.Snapshot
import es.jvbabi.trails.domain.model.User
import es.jvbabi.trails.domain.repository.Location
import es.jvbabi.trails.page.Screen
import es.jvbabi.trails.page.devices.main.DevicesTab
import es.jvbabi.trails.page.home.components.*
import es.jvbabi.trails.page.shares.main.SharesScreen
import es.jvbabi.trails.utils.IntPaddingValues
import es.jvbabi.trails.utils.blendColor
import es.jvbabi.trails.utils.padding
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*
import kotlin.uuid.Uuid

const val DEBUG = false

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    mapViewModel: MapViewModel,
    backstack: MutableList<Screen>,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mapState by mapViewModel.state.collectAsStateWithLifecycle()

    HomeContent(
        state = state,
        mapState = mapState,
        backStack = backstack,
        onEvent = viewModel::onEvent,
        onMapEvent = mapViewModel::onEvent,
    )

    val localDensity = LocalDensity.current
    LaunchedEffect(localDensity.density) {
        mapViewModel.setup(
            localDensity = localDensity.density
        )
    }

    if (DEBUG) {
        val mapContentPadding by mapViewModel.mapContentPadding.collectAsStateWithLifecycle()
        if (mapContentPadding != null) {
            val dpPadding = with(localDensity) {
                PaddingValues(
                    top = mapContentPadding!!.top.toDp(),
                    start = mapContentPadding!!.start.toDp(),
                    end = mapContentPadding!!.end.toDp(),
                    bottom = mapContentPadding!!.bottom.toDp(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(dpPadding)
                    .border(1.dp, Color.Red)
            )
        }
    }
}

@Composable
fun HomeContent(
    state: HomeState,
    mapState: MapState,
    backStack: MutableList<Screen>,
    onEvent: (event: HomeEvent) -> Unit,
    onMapEvent: (event: MapEvent) -> Unit,
) {
    val cardCollapsedHeight = 72.dp
    var fabHeight by remember { mutableStateOf(48.dp) }

    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                onMapEvent(MapEvent.OnViewportResize(size))
            }
    ) {
        val draggableCardSheetState = rememberDraggableCardSheetState(
            expandedHeight = maxHeight,
            semiExpandedHeight = 350.dp,
            collapsedHeight = cardCollapsedHeight,
            initialValue = CardSheetValue.Collapsed,
        )
        DraggableCardSheet(
            modifier = Modifier.fillMaxSize(),
            state = draggableCardSheetState,
            content = {
                val density = LocalDensity.current

                Scaffold { scaffoldPadding ->
                    val contentPadding = (draggableCardSheetState.backgroundContentPaddingValues + es.jvbabi.trails.utils.PaddingValues(top = scaffoldPadding.calculateTopPadding())).assureNonNegative()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(hazeState)
                    ) {
                        Box(Modifier.fillMaxSize())
                        Map(
                            state = mapState,
                            onDeviceClick = { device ->
                                scope.launch { draggableCardSheetState.semiExpand() }
                                onEvent(HomeEvent.SelectTab(HomeState.Tab.MyDevices(es.jvbabi.trails.page.devices.Screen.Device(device.device.id))))
                            },
                            onUserDragStart = { onMapEvent(MapEvent.UserDragged) },
                        )

                        Box(
                            modifier = Modifier
                                .padding(contentPadding)
                                .fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 48.dp) // Map Compass
                                    .padding(16.dp)
                                    .align(Alignment.TopEnd)
                            ) {
                                IconButton(
                                    onClick = { backStack.add(Screen.Settings) },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = PhIcons.Regular.Gear,
                                        contentDescription = stringResource(Res.string.settings_title)
                                    )
                                }
                            }

                            val trackingLabel = stringResource(when (mapState.trackingMode) {
                                MapState.TrackingMode.None -> Res.string.home_map_overview
                                MapState.TrackingMode.Overview -> Res.string.home_map_own_location
                                MapState.TrackingMode.OwnLocation -> Res.string.home_map_overview
                            })

                            Column(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onSizeChanged { size ->
                                        fabHeight = with(density) { size.height.toDp() }
                                    }
                                    .padding(bottom = 8.dp),
                            ) {
                                ExtendedFloatingActionButton(
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                    text = {
                                        AnimatedContent(
                                            targetState = trackingLabel
                                        ) { label ->
                                            Text(label)
                                        }
                                    },
                                    icon = {
                                        AnimatedContent(
                                            targetState = mapState.trackingMode,
                                            modifier = Modifier.size(24.dp),
                                        ) { currentTrackingMode ->
                                            Icon(
                                                imageVector = when (currentTrackingMode) {
                                                    MapState.TrackingMode.None, MapState.TrackingMode.OwnLocation -> PhIcons.Regular.CornersOut
                                                    MapState.TrackingMode.Overview -> PhIcons.Regular.GpsFix
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    },
                                    onClick = { onMapEvent(MapEvent.ToggleTrackingMode) },
                                )
                            }
                        }
                    }
                }
            },
            cardContent = { contentPadding ->
                val hazeStyle = HazeMaterials.thin()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeEffect(hazeState) {
                            blurEffect {
                                blurRadius = 8.dp + draggableCardSheetState.progress * 4.dp
                                style = hazeStyle
                            }
                        }
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = draggableCardSheetState.progress/(1/0.8f) + 0.8f))
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = (animateDpAsState(if (draggableCardSheetState.isUserDragging) 5.dp else 4.dp).value + ((contentPadding.top - 8.dp) * draggableCardSheetState.expandedProgress)).coerceAtLeast(0.dp))
                            .align(Alignment.TopCenter)
                            .width(animateDpAsState(if (draggableCardSheetState.isUserDragging) 52.dp else 40.dp).value)
                            .height(animateDpAsState(if (draggableCardSheetState.isUserDragging) 2.dp else 4.dp).value)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    val defaultContentBottomPadding = cardCollapsedHeight + 8.dp + 16.dp * draggableCardSheetState.progress

                    Box(
                        modifier = Modifier
                            .padding(bottom = max(defaultContentBottomPadding, WindowInsets.ime.asPaddingValues().calculateBottomPadding()))
                            .fillMaxSize()
                            .clipToBounds()
                            .bottomFadeOut(active = draggableCardSheetState.isUserDragging && draggableCardSheetState.expandedProgress == 0.0f)
                    ) {
                        if (!LocalInspectionMode.current) AnimatedContent(
                            targetState = state.selectedTab,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = { fadeIn(tween(100)) togetherWith fadeOut(tween(100)) }
                        ) { selectedTab ->
                            when (selectedTab) {
                                is HomeState.Tab.MyDevices -> DevicesTab(
                                    contentPadding = contentPadding,
                                    nestedScrollConnection = draggableCardSheetState.nestedScrollConnection,
                                    initialRoute = selectedTab.initialRoute,
                                    onFocusDevice = { deviceId ->
                                        onMapEvent(MapEvent.FocusDevice(deviceId))
                                        if (deviceId != null && draggableCardSheetState.targetValue == CardSheetValue.Expanded) {
                                            scope.launch { draggableCardSheetState.semiExpand() }
                                        }
                                    }
                                )
                                HomeState.Tab.Things -> Text(stringResource(Res.string.home_things_placeholder))
                                HomeState.Tab.Shares -> SharesScreen(
                                    nestedScrollConnection = draggableCardSheetState.nestedScrollConnection,
                                    onExpandCard = { scope.launch { draggableCardSheetState.expand() } },
                                    onSemiExpandCard = { scope.launch { draggableCardSheetState.semiExpand() } },
                                    contentPadding = contentPadding,
                                )
                            }

                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(blendColor(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, draggableCardSheetState.progress/2f).copy(alpha = draggableCardSheetState.progress))
                            .padding(bottom = 16.dp * draggableCardSheetState.progress)
                            .fillMaxWidth()
                            .height(cardCollapsedHeight + 8.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        HorizontalDivider(Modifier.alpha(draggableCardSheetState.progress))
                        NavigationBar(
                            modifier = Modifier.weight(1f),
                            selectedTab = state.selectedTab,
                            draggableCardSheetState = draggableCardSheetState,
                            onSelect = { onEvent(HomeEvent.SelectTab(it)) }
                        )
                    }
                }
            }
        )

        val density = LocalDensity.current
        val windowInsets = WindowInsets.safeContent.asPaddingValues()
        val intPaddingValues by remember(draggableCardSheetState.targetValue, fabHeight) {
            mutableStateOf(with(density) {
                IntPaddingValues(
                    top = windowInsets.calculateTopPadding().roundToPx(),
                    bottom = (draggableCardSheetState.targetBackgroundContentPaddingValues.bottom.coerceAtMost(draggableCardSheetState.semiContainerPadding + draggableCardSheetState.semiExpandedHeight) + fabHeight).roundToPx(),
                    start = 16.dp.roundToPx(),
                    end = 16.dp.roundToPx(),
                ) + IntPaddingValues(16.dp.roundToPx())
            })
        }

        LaunchedEffect(intPaddingValues) {
            onMapEvent(MapEvent.OnMapContentAreaPadding(intPaddingValues))
        }
    }
}

@Composable
internal fun rememberMockMapState(): MapState {
    var deviceImage by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(Unit) {
        deviceImage = Res.readBytes("files/device-sample.jpg")
    }

    val now = LocalDateTime(2026, Month.MAY, 17, 15, 0, 0)
    val mockDevice = Device(
        id = Uuid.random(),
        manufacturer = "Google",
        model = "Pixel 7",
        friendlyName = "Pixel 7",
        displayName = "Julius' Pixel 7",
        owner = User(
            id = Uuid.random(),
            homeserver = "trails.jvbabi.es",
            username = "julius",
        ),
        batteryState = Device.BatteryState.Shared(percentage = 85, isCharging = false),
    )
    val mockLocation = Location(
        latitude = 40.4168,
        longitude = -3.7038,
        bearing = 45f,
        bearingAccuracy = 10f,
        locationAccuracy = 20f,
        time = now,
    )
    val mockSnapshot = Snapshot(
        id = Uuid.random(),
        device = mockDevice,
        time = now,
        location = mockLocation,
        batteryState = es.jvbabi.trails.domain.repository.BatteryState(percentage = 72, isCharging = true),
        isSynced = true,
    )

    return MapState(
        ownLocation = Location(
            latitude = 40.4178,
            longitude = -3.7030,
            bearing = 0f,
            bearingAccuracy = null,
            locationAccuracy = 15f,
            time = now,
        ),
        devices = listOf(
            HomeState.HomeDevice(
                device = mockDevice,
                image = deviceImage,
                snapshot = mockSnapshot,
            ),
        ),
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
fun HomeScreenPreview() {
    HomeContent(
        state = HomeState(),
        mapState = rememberMockMapState(),
        backStack = mutableListOf(),
        onEvent = {},
        onMapEvent = {},
    )
}

fun Modifier.bottomFadeOut(
    height: Dp = 48.dp,
    active: Boolean = true,
) = composed {

    val fadeAlpha by animateFloatAsState(
        targetValue = if (active) 0f else 1f
    )

    this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeHeightPx = height.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Black.copy(alpha = fadeAlpha)),
                    startY = size.height - fadeHeightPx,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
