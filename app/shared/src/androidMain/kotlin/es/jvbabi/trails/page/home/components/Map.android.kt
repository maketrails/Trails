@file:OptIn(MapboxExperimental::class)

package es.jvbabi.trails.page.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import es.jvbabi.trails.utils.averageLocation
import es.jvbabi.trails.utils.bundleOverlappingPins
import es.jvbabi.trails.utils.bundleSpread
import es.jvbabi.trails.ui.components.desaturated
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import es.jvbabi.trails.utils.ring
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Composable
fun DeviceMarker(
    imageBytes: ByteArray?,
    /**
     * Whether the device is reachable. An unreachable one is drawn without colour, so
     * a pin that marks a last known position rather than a current one says so.
     */
    isOnline: Boolean = true,
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
                    .size(32.dp)
                    .desaturated(!isOnline),
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
                                    modifier = Modifier
                                        .size(BUNDLE_IMAGE_SIZE)
                                        .desaturated(device.onlineState?.isOnline == false),
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
 * The ground a bundle covers, for the ones whose members are genuinely far apart: a full
 * border around a translucent body. Drawn in real distances rather than in pixels, so it
 * keeps covering the same ground at every zoom.
 */
@Composable
private fun BundleSpreadCircle(spread: BundleSpread, darkMap: Boolean) {
    // The target is set as the state is created, so the circle is on its way in from the
    // very first frame. Nothing has to fire afterwards for it to get there.
    val appearance = remember { MutableTransitionState(false).apply { targetState = true } }
    val transition = rememberTransition(appearance, label = "bundle spread")
    val scale by transition.animateFloat(
        transitionSpec = { tween(durationMillis = BUNDLE_ANIMATION_MS, easing = FastOutSlowInEasing) },
        label = "bundle spread scale",
    ) { shown -> if (shown) 1f else 0.6f }

    val color = if (darkMap) SPREAD_COLOR_DARK else SPREAD_COLOR_LIGHT
    // It grows out of its centre; the opacity is left alone on purpose, so an animation
    // that never runs leaves a slightly small circle rather than no circle at all.
    val ring = spread.ring(scale = scale.toDouble())
        .map { point -> Point.fromLngLat(point.longitude, point.latitude) }

    PolygonAnnotation(points = listOf(ring)) {
        fillColor = color
        fillOpacity = SPREAD_FILL_OPACITY
    }
    PolylineAnnotation(points = ring) {
        lineColor = color
        lineWidth = SPREAD_LINE_WIDTH
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
            // bundling on every pan, zoom, rotation and tilt. Worked out anew on every
            // composition rather than kept in a remember: a cache here could go on
            // describing a grouping the camera has long moved past.
            val camera = mapViewportState.cameraState
            val currentMap = mapboxMap
            val bundles = if (currentMap == null || camera == null) {
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
            val bundleOf = bundles
                .flatMap { bundle -> bundle.items.map { member -> member.device.id to bundle } }
                .toMap()

            /*
             * One annotation per device, for as long as that device exists. Bundling only
             * decides what an annotation shows and where it sits — never whether it is
             * there. Nothing about the drawn state is stored: it is worked out from the
             * devices and the camera on every composition, so no camera move, no
             * navigation and no reordering can leave a device without a marker.
             */
            pinnedDevices.forEach { device ->
                key(device.device.id) {
                    val members = bundleOf[device.device.id]?.items ?: listOf(device)
                    val locations = members.map { member ->
                        val location = member.snapshot!!.location
                        Location(latitude = location.latitude, longitude = location.longitude)
                    }

                    // A bundle is drawn by exactly one of its members, chosen by id so the
                    // choice can't wander with the order the devices arrive in. The others
                    // stay where they are, hidden.
                    val drawsMarker = members.minOf { it.device.id.toString() } == device.device.id.toString()

                    // Only bundles standing for far-apart devices get a circle; a ring
                    // around pins that sit on the same spot says nothing.
                    val spread = if (members.size > 1) bundleSpread(locations) else null

                    // Anchored geographically, never at a projected pixel: a coordinate read
                    // off the screen is only true for the camera that was up when it was
                    // read, and would strand the marker the moment the camera moves on.
                    val anchor = when {
                        members.size == 1 -> locations.first()
                        spread != null -> spread.top
                        else -> averageLocation(locations)
                    }

                    ViewAnnotation(
                        options = viewAnnotationOptions {
                            geometry(Point.fromLngLat(anchor.longitude, anchor.latitude))
                            allowOverlap(true)
                            allowOverlapWithPuck(true)
                            visible(drawsMarker)
                            annotationAnchor {
                                anchor(ViewAnnotationAnchor.BOTTOM)
                            }
                        }
                    ) {
                        // Grows in when this device takes over the drawing — on its own or
                        // for its bundle — and shrinks back as it hands it on. The scale is
                        // derived from that, and nothing here touches opacity: a marker can
                        // come out a little small, never invisible.
                        val appearance by animateFloatAsState(
                            targetValue = if (drawsMarker) 1f else 0.6f,
                            animationSpec = tween(durationMillis = BUNDLE_ANIMATION_MS, easing = FastOutSlowInEasing),
                            label = "marker appearance",
                        )

                        Box(
                            modifier = Modifier.graphicsLayer {
                                scaleX = appearance
                                scaleY = appearance
                                // Pivots on the tip, so a marker grows out of its coordinate
                                // rather than out of its own middle.
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                        ) {
                            // Pin and bundle trade places inside one and the same annotation,
                            // so the swap is a crossfade rather than one marker vanishing and
                            // another appearing somewhere.
                            AnimatedContent(
                                targetState = drawsMarker && members.size > 1,
                                transitionSpec = {
                                    val spec = tween<Float>(
                                        durationMillis = BUNDLE_ANIMATION_MS,
                                        easing = FastOutSlowInEasing,
                                    )
                                    val origin = TransformOrigin(0.5f, 1f)
                                    (fadeIn(spec) + scaleIn(spec, initialScale = 0.6f, transformOrigin = origin))
                                        .togetherWith(
                                            fadeOut(spec) + scaleOut(spec, targetScale = 0.6f, transformOrigin = origin)
                                        )
                                },
                                label = "pin or bundle",
                            ) { drawsBundle ->
                                if (drawsBundle) {
                                    DeviceBundleMarker(
                                        devices = members,
                                        onDeviceClick = onDeviceClick,
                                    )
                                } else {
                                    DeviceMarker(
                                        imageBytes = device.image,
                                        isOnline = device.onlineState?.isOnline != false,
                                        onClick = { onDeviceClick(device) },
                                    )
                                }
                            }
                        }
                    }

                    if (drawsMarker && spread != null) {
                        BundleSpreadCircle(spread = spread, darkMap = darkMap)
                    }
                }
            }
        }
    }
}
