@file:OptIn(MapboxExperimental::class)

package es.jvbabi.trails.page.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.Res
import trails.app.shared.generated.resources.home_map_compass
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.*
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.ComposeMapInitOptions
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolygonAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.StandardStyleState
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.viewannotation.ViewAnnotationUpdateMode
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import es.jvbabi.trails.LocalAppTheme
import es.jvbabi.trails.domain.repository.Theme
import es.jvbabi.trails.page.home.HomeState
import es.jvbabi.trails.page.home.MapState
import es.jvbabi.trails.utils.BundleSpread
import es.jvbabi.trails.utils.Location
import es.jvbabi.trails.utils.PinBundle
import es.jvbabi.trails.utils.PinPoint
import es.jvbabi.trails.utils.PinSize
import es.jvbabi.trails.utils.bundleOverlappingPins
import es.jvbabi.trails.utils.bundleSpread
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import es.jvbabi.trails.utils.ring
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Composable
fun DeviceMarker(
    imageBytes: ByteArray?,
    onClick: () -> Unit,
) {
    val arrowShape = remember {
        GenericShape { size, _ ->
            moveTo(size.width / 2f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
            close()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // No ripple on the outer Box
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                // Ripple is drawn here, clipped to CircleShape
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(),
                )
        )

        val bitmap = rememberBitmapFromBytes(imageBytes)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(8.dp, 8.dp)
                .clip(arrowShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Preview
@Composable
fun DeviceMarkerPreview() {
    DeviceMarker(
        imageBytes = null,
        onClick = {},
    )
}

/*
 * Geometry of a bundle, in dp. Devices that cover each other on screen are drawn as one
 * pill with their images side by side — see bundleOverlappingPins, which has to be told
 * the very size drawn here or it would bundle at a size the map never shows.
 */
private val PIN_SIZE = 64.dp
private val BUNDLE_AVATAR_SIZE = 40.dp
private val BUNDLE_IMAGE_SIZE = 28.dp
private val BUNDLE_GAP = 4.dp
private val BUNDLE_PADDING = 6.dp
private val BUNDLE_TAIL_WIDTH = 12.dp
private val BUNDLE_TAIL_HEIGHT = 8.dp

/** Members per row. Beyond that the pill wraps, so it grows in height, not endlessly wide. */
private const val BUNDLE_COLUMNS = 4

/** Same length as the map's other movements, so a bundle and its circle arrive together. */
private const val BUNDLE_ANIMATION_MS = 220

/**
 * The app's palette is monochrome, so the circle around a far-flung bundle brings its own
 * blue — the same one the web app draws it in.
 */
private val SPREAD_COLOR_LIGHT = Color(0xFF2563EB)
private val SPREAD_COLOR_DARK = Color(0xFF60A5FA)
private const val SPREAD_FILL_OPACITY = 0.18
private const val SPREAD_LINE_WIDTH = 2.5

/** The size a bundle of [count] devices is drawn at, a single pin included. */
private fun bundleSize(count: Int): DpSize {
    if (count <= 1) return DpSize(PIN_SIZE, PIN_SIZE)

    val columns = min(count, BUNDLE_COLUMNS)
    val rows = ceil(count.toDouble() / BUNDLE_COLUMNS).toInt()
    return DpSize(
        width = BUNDLE_PADDING * 2 + BUNDLE_AVATAR_SIZE * columns + BUNDLE_GAP * (columns - 1),
        height = BUNDLE_PADDING * 2 + BUNDLE_AVATAR_SIZE * rows + BUNDLE_GAP * (rows - 1) + BUNDLE_TAIL_HEIGHT,
    )
}

/** The same size in screen pixels, which is where bundling is decided. */
private fun bundleSizePx(count: Int, density: Density): PinSize = with(density) {
    val size = bundleSize(count)
    PinSize(width = size.width.toPx().toDouble(), height = size.height.toPx().toDouble())
}

/**
 * The devices of one bundle, side by side in a pill. Each keeps its own click, so a
 * bundled device is opened exactly like a lone one — by tapping it.
 */
@Composable
fun DeviceBundleMarker(
    devices: List<HomeState.HomeDevice>,
    onDeviceClick: (HomeState.HomeDevice) -> Unit,
) {
    val arrowShape = remember {
        GenericShape { size, _ ->
            moveTo(size.width / 2f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
            close()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(BUNDLE_PADDING),
            verticalArrangement = Arrangement.spacedBy(BUNDLE_GAP),
        ) {
            devices.chunked(BUNDLE_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(BUNDLE_GAP)) {
                    row.forEach { device ->
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(BUNDLE_AVATAR_SIZE)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = ripple(),
                                    onClick = { onDeviceClick(device) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bitmap = rememberBitmapFromBytes(device.image)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(BUNDLE_IMAGE_SIZE),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(BUNDLE_TAIL_WIDTH, BUNDLE_TAIL_HEIGHT)
                .clip(arrowShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * Grows a marker out of the coordinate it marks when it first appears. A bundle and the
 * pins it is made of trade places at the same spot and in the same instant, and cutting
 * either of them would read as a flicker.
 *
 * Unlike the web app there is no matching shrink-out: Compose drops the marker that is
 * leaving straight away, so only the arriving end can be animated.
 */
@Composable
private fun GrowIn(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = BUNDLE_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "marker grow-in",
    )

    Box(
        modifier = Modifier.graphicsLayer {
            val scale = 0.55f + 0.45f * progress
            scaleX = scale
            scaleY = scale
            alpha = progress
            // Pivots on the tip, so the marker grows out of its coordinate rather than
            // out of its own middle.
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    ) {
        content()
    }
}

/**
 * The ground a bundle covers, for the ones whose members are genuinely far apart: a full
 * border around a translucent body. Drawn in real distances rather than in pixels, so it
 * keeps covering the same ground at every zoom.
 */
@Composable
private fun BundleSpreadCircle(spread: BundleSpread, darkMap: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = BUNDLE_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "bundle spread",
    )
    if (progress <= 0f) return

    val color = if (darkMap) SPREAD_COLOR_DARK else SPREAD_COLOR_LIGHT
    // Grows out of its centre rather than fading in on the spot, so it reads as the
    // bundle taking up its ground.
    val ring = spread.ring(scale = 0.6 + 0.4 * progress)
        .map { point -> Point.fromLngLat(point.longitude, point.latitude) }

    PolygonAnnotation(points = listOf(ring)) {
        fillColor = color
        fillOpacity = SPREAD_FILL_OPACITY * progress
    }
    PolylineAnnotation(points = ring) {
        lineColor = color
        lineWidth = SPREAD_LINE_WIDTH
        lineOpacity = progress.toDouble()
    }
}

@Composable
actual fun Map(
    state: MapState,
    onDeviceClick: (HomeState.HomeDevice) -> Unit,
    onUserDragStart: () -> Unit,
) {
    val mapViewportState = rememberMapViewportState {
        flyTo(
            cameraOptions = cameraOptions {
                center(Point.fromLngLat(10.4515, 51.1657))
                zoom(6.0)
                pitch(0.0)
            },
            MapAnimationOptions.mapAnimationOptions { duration(0) }
        )
    }

    // Which of the two styles is up. The circle around a far-flung bundle picks its blue
    // by it, so it is read here rather than inside the style block.
    val darkMap = when (LocalAppTheme.current) {
        Theme.Dark -> true
        Theme.Light -> false
        Theme.System -> isSystemInDarkTheme()
    }

    // The map itself, kept for asking where a coordinate lands on screen — which is what
    // the bundling is decided on.
    var mapboxMap by remember { mutableStateOf<MapboxMap?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            style = {
                MapStyle(style = if (darkMap) Style.TRAFFIC_NIGHT else Style.STANDARD)
            },
            scaleBar = {
                ScaleBar(
                    modifier = Modifier
                        .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 8.dp)
                        .padding(horizontal = 16.dp),
                )
            },
            compass = {
                Compass(
                    fadeWhenFacingNorth = false,
                    resetToNorthUponClick = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 8.dp)
                        .padding(horizontal = 16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.mapbox_compass_icon),
                        contentDescription = stringResource(Res.string.home_map_compass)
                    )
                }
            },
            composeMapInitOptions = ComposeMapInitOptions(
                mapOptions = MapOptions.Builder().build(),
                textureView = true,
            ),
        ) {
            MapEffect(Unit) { mapView ->
                mapboxMap = mapView.mapboxMap
                mapView.location.updateSettings {
                    locationPuck = createDefault2DPuck(withBearing = true)
                    puckBearingEnabled = true
                    puckBearing = PuckBearing.HEADING
                    enabled = true
                }
                mapView.mapboxMap.setBounds(
                    CameraBoundsOptions.Builder()
                        .maxZoom(18.0)
                        .build()
                )
                mapView.mapboxMap.symbolScaleBehavior = SymbolScaleBehavior.fixed(25f)
                mapView.viewAnnotationManager.setViewAnnotationUpdateMode(ViewAnnotationUpdateMode.MAP_SYNCHRONIZED)
            }

            MapEffect(state.targetCameraState) { mapView ->
                val camera = state.targetCameraState ?: return@MapEffect
                mapViewportState.flyTo(
                    cameraOptions = cameraOptions {
                        center(Point.fromLngLat(camera.centerLongitude, camera.centerLatitude))
                        zoom(camera.zoom)
                        pitch(camera.pitch)
                        bearing(camera.bearing)
                    },
                    MapAnimationOptions.mapAnimationOptions {
                        duration(1.5.seconds.inWholeMilliseconds)
                    }
                )
            }

            DisposableMapEffect(Unit) { mapView ->
                val moveListener = object : OnMoveListener {
                    override fun onMoveBegin(detector: MoveGestureDetector) { onUserDragStart() }
                    override fun onMove(detector: MoveGestureDetector): Boolean = false
                    override fun onMoveEnd(detector: MoveGestureDetector) {}
                }

                mapView.gestures.addOnMoveListener(moveListener)
                onDispose {
                    mapView.gestures.removeOnMoveListener(moveListener)
                }
            }

            val density = LocalDensity.current

            // Everything that gets a pin: every device with a location, except this one —
            // that one is the location puck.
            val pinnedDevices = state.devices
                .filterNot { device -> device.device.id == state.currentDevice?.id }
                .filter { it.snapshot != null }

            // Which pins cover each other is decided in screen space, so the answer changes
            // with the camera — reading the viewport's camera state is what redoes the
            // bundling on every pan, zoom, rotation and tilt.
            val camera = mapViewportState.cameraState
            val currentMap = mapboxMap
            val bundles = remember(pinnedDevices, camera, currentMap, density) {
                if (currentMap == null) {
                    // Nothing to project against yet; every device stands on its own.
                    pinnedDevices.map { device -> PinBundle(listOf(device), PinPoint(0.0, 0.0)) }
                } else {
                    bundleOverlappingPins(
                        items = pinnedDevices,
                        sizeOf = { count -> bundleSizePx(count, density) },
                        positionOf = { device ->
                            val location = device.snapshot!!.location
                            val pixel = currentMap.pixelForCoordinate(
                                Point.fromLngLat(location.longitude, location.latitude)
                            )
                            PinPoint(pixel.x, pixel.y)
                        },
                    )
                }
            }

            bundles.forEach { bundle ->
                // Keyed by its members, so a bundle that gains or loses one is a different
                // bundle and is drawn anew — which is what plays its grow-in.
                key(bundle.items.joinToString("|") { it.device.id.toString() }) {
                    if (bundle.items.size == 1) {
                        val device = bundle.items.first()
                        val position = device.snapshot!!.location

                        ViewAnnotation(
                            options = viewAnnotationOptions {
                                geometry(Point.fromLngLat(position.longitude, position.latitude))
                                allowOverlap(true)
                                allowOverlapWithPuck(true)
                                annotationAnchor {
                                    anchor(ViewAnnotationAnchor.BOTTOM)
                                }
                            }
                        ) {
                            GrowIn {
                                DeviceMarker(
                                    imageBytes = device.image,
                                    onClick = {
                                        onDeviceClick(device)
                                    },
                                )
                            }
                        }
                    } else {
                        // Only bundles that stand for far-apart devices get a circle; the
                        // rest are pins on the same spot, and a ring around those says
                        // nothing.
                        val spread = bundleSpread(
                            bundle.items.map { device ->
                                val location = device.snapshot!!.location
                                Location(latitude = location.latitude, longitude = location.longitude)
                            }
                        )

                        // A bundle standing for ground rather than for a spot is anchored at
                        // the top of its circle, so it points at what it covers instead of
                        // hiding the middle of it.
                        val anchor = spread?.top ?: currentMap
                            ?.coordinateForPixel(ScreenCoordinate(bundle.position.x, bundle.position.y))
                            ?.let { point -> Location(latitude = point.latitude(), longitude = point.longitude()) }

                        if (anchor != null) {
                            ViewAnnotation(
                                options = viewAnnotationOptions {
                                    geometry(Point.fromLngLat(anchor.longitude, anchor.latitude))
                                    allowOverlap(true)
                                    allowOverlapWithPuck(true)
                                    annotationAnchor {
                                        anchor(ViewAnnotationAnchor.BOTTOM)
                                    }
                                }
                            ) {
                                GrowIn {
                                    DeviceBundleMarker(
                                        devices = bundle.items,
                                        onDeviceClick = onDeviceClick,
                                    )
                                }
                            }
                        }

                        if (spread != null) BundleSpreadCircle(spread = spread, darkMap = darkMap)
                    }
                }
            }
        }
    }
}
