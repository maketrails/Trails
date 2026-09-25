@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.trails.page.setings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowLeft
import com.phosphor.icons.regular.BellRinging
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.CircleHalf
import com.phosphor.icons.regular.Moon
import com.phosphor.icons.regular.Palette
import com.phosphor.icons.regular.Path
import com.phosphor.icons.regular.Sun
import es.jvbabi.trails.domain.repository.Theme
import es.jvbabi.trails.ui.components.SteppedSlider
import es.jvbabi.trails.ui.components.settings.SettingsCategory
import es.jvbabi.trails.ui.components.settings.SettingsCheckboxItem
import es.jvbabi.trails.ui.components.settings.SettingsDefaults
import es.jvbabi.trails.ui.components.settings.SettingsItem
import nl.jacobras.humanreadable.DistanceUnit
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*
import kotlin.math.abs

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.minimumMovementMeters == null) return

    SettingsContent(
        state = state,
        onBack = onBack,
        onEvent = viewModel::onEvent
    )
}

@Composable
fun SettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        // The map is rendered below the navigation display and stays there while settings are
        // open, so this surface has to swallow the gestures that its own content does not use.
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = PhIcons.Regular.ArrowLeft,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
        ) {

            SettingsCategory(title = stringResource(Res.string.settings_section_interface)) {
                SettingsItem(
                    title = stringResource(Res.string.settings_theme_title),
                    icon = PhIcons.Regular.Palette,
                    bottomContent = {
                        ThemeSelector(
                            selectedTheme = state.appTheme,
                            onThemeSelected = { onEvent(SettingsEvent.SetAppTheme(it)) },
                        )
                    },
                )
            }

            SettingsCategory(title = stringResource(Res.string.settings_section_tracking)) {
                val meterValues = SettingsState.DEFAULT_MINIMUM_MOVEMENT_METER_VALUES

                // Nearest default to the persisted value, so a value outside the list still maps to a step.
                val selectedIndex = remember(state.minimumMovementMeters) {
                    val persisted = state.minimumMovementMeters ?: meterValues.first()
                    meterValues.indices.minBy { abs(meterValues[it] - persisted) }
                }

                SettingsItem(
                    title = stringResource(Res.string.settings_minimum_movement_title),
                    subtitle = stringResource(Res.string.settings_minimum_movement_description),
                    icon = PhIcons.Regular.Path,
                    bottomContent = {
                        SteppedSlider(
                            stepCount = meterValues.size,
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { index ->
                                onEvent(SettingsEvent.UpdateMinimumMovementMeters(meterValues[index]))
                            },
                            thumbLabel = { index -> HumanReadable.distance(meterValues[index], DistanceUnit.Meter) },
                        )
                    },
                )

                if (state.currentHomeserverUrl != null) {
                    SettingsCheckboxItem(
                        title = stringResource(Res.string.settings_show_homeserver_in_notification_title),
                        subtitle = stringResource(Res.string.settings_show_homeserver_in_notification_description, state.currentHomeserverUrl),
                        icon = PhIcons.Regular.BellRinging,
                        checked = state.showHomeserverInPersistentNotification,
                        onCheckedChange = { onEvent(SettingsEvent.ToggleShowHomeserverInPersistentNotification(it)) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Actions and debug information that are not settings in their own right yet.
            Column(
                modifier = Modifier.padding(horizontal = SettingsDefaults.HorizontalStartPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onEvent(SettingsEvent.RequestLocationPermissions) },
                    enabled = state.hasLocationPermissions == false
                ) {
                    Text(stringResource(Res.string.settings_request_location_permissions))
                }

                Button(
                    onClick = { onEvent(SettingsEvent.RequestNotificationPermissions) },
                    enabled = state.hasNotificationPermissions == false
                ) {
                    Text(stringResource(Res.string.settings_request_notification_permissions))
                }

                Button(
                    onClick = { onEvent(SettingsEvent.RequestFullscreenIntentPermissions) },
                    enabled = state.hasFullscreenIntentPermissions == false
                ) {
                    Text(stringResource(Res.string.settings_request_fullscreen_permissions))
                }

                Button(
                    onClick = { onEvent(SettingsEvent.RequestUnrestrictedBatteryBackgroundUsage) },
                    enabled = state.hasUnrestrictedBatteryBackgroundUsage == false
                ) {
                    Text(stringResource(Res.string.settings_disable_battery_optimizations))
                }

                Button(onClick = { onEvent(SettingsEvent.OpenLoginDialog) }) {
                    Text(stringResource(Res.string.settings_login))
                }

                Text(stringResource(Res.string.settings_debug_server, state.currentHomeserverUrl.toString()))
                Text(stringResource(Res.string.settings_debug_device, state.thisDeviceId.toString(), state.thisDevice?.displayName.toString()))
                Text(stringResource(Res.string.settings_debug_user_id, state.userId.toString()))
                Text(stringResource(
                    Res.string.settings_debug_unsynced_snapshots,
                    state.unsyncedSnapshotCount?.let { HumanReadable.number(it) }
                        ?: stringResource(Res.string.settings_debug_unknown_count),
                ))

                Button(
                    onClick = { onEvent(SettingsEvent.RingDevice) }
                ) {
                    Text(stringResource(Res.string.settings_ring_device))
                }

                Button(
                    onClick = {
                        if (state.isBackgroundTrackingServiceRunning) onEvent(SettingsEvent.StopTracking)
                        else onEvent(SettingsEvent.StartTracking)
                    }
                ) {
                    Text(stringResource(if (state.isBackgroundTrackingServiceRunning) Res.string.settings_stop_tracking else Res.string.settings_start_tracking))
                }
            }
        }
    }

    if (state.showLoginDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(SettingsEvent.CloseLoginDialog) },
            title = { Text(stringResource(Res.string.settings_login)) },
            text = {
                Column {
                    TextField(
                        value = state.loginDialogHomeServerUrl,
                        onValueChange = { onEvent(SettingsEvent.UpdateHomeServerUrl(it)) },
                        label = { Text(stringResource(Res.string.settings_login_homeserver_label)) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onEvent(SettingsEvent.Login)
                }) {
                    Text(stringResource(Res.string.common_ok))
                }
            }
        )
    }
}

/** Segmented buttons to pick the app [Theme]. */
@Composable
private fun ThemeSelector(
    selectedTheme: Theme?,
    onThemeSelected: (Theme) -> Unit,
) {
    val options = listOf(
        Triple(Theme.System, PhIcons.Regular.CircleHalf, Res.string.settings_theme_auto),
        Triple(Theme.Light, PhIcons.Regular.Sun, Res.string.settings_theme_light),
        Triple(Theme.Dark, PhIcons.Regular.Moon, Res.string.settings_theme_dark),
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (theme, icon, label) ->
            SegmentedButton(
                selected = selectedTheme == theme,
                onClick = { onThemeSelected(theme) },
                icon = {
                    AnimatedContent(targetState = selectedTheme == theme) { isSelected ->
                        Icon(
                            imageVector = if (isSelected) PhIcons.Regular.Check else icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                label = { Text(stringResource(label)) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            )
        }
    }
}

@Composable
@Preview
private fun SettingsPreview() {
    SettingsContent(
        onBack = {},
        state = SettingsState(
            showLoginDialog = false,
            loginDialogHomeServerUrl = "https://trails.werkbank.space",
            hasLocationPermissions = true,
            currentHomeserverUrl = "trails.example.com",
            appTheme = Theme.Light,
            minimumMovementMeters = 10,
        ),
        onEvent = {}
    )
}