@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.trails.page.setings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.jvbabi.trails.domain.repository.Theme
import es.jvbabi.trails.ui.components.SteppedSlider
import nl.jacobras.humanreadable.DistanceUnit
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.painterResource
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
                            painter = painterResource(Res.drawable.arrow_left),
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
                .padding(contentPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = stringResource(Res.string.settings_section_interface).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp)
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected = state.appTheme == Theme.System,
                    onClick = { onEvent(SettingsEvent.SetAppTheme(Theme.System)) },
                    icon = {
                        AnimatedContent(
                            targetState = state.appTheme == Theme.System,
                        ) { isSelected ->
                            Icon(
                                painter = painterResource(if (!isSelected) Res.drawable.sun_moon else Res.drawable.check),
                                contentDescription = stringResource(Res.string.settings_theme_system),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    label = {
                        Text(stringResource(Res.string.settings_theme_auto))
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                )
                SegmentedButton(
                    selected = state.appTheme == Theme.Light,
                    onClick = { onEvent(SettingsEvent.SetAppTheme(Theme.Light)) },
                    icon = {
                        AnimatedContent(
                            targetState = state.appTheme == Theme.Light,
                        ) { isSelected ->
                            Icon(
                                painter = painterResource(if (!isSelected) Res.drawable.sun else Res.drawable.check),
                                contentDescription = stringResource(Res.string.settings_theme_light),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    label = {
                        Text(stringResource(Res.string.settings_theme_light))
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                )
                SegmentedButton(
                    selected = state.appTheme == Theme.Dark,
                    onClick = { onEvent(SettingsEvent.SetAppTheme(Theme.Dark)) },
                    icon = {
                        AnimatedContent(
                            targetState = state.appTheme == Theme.Dark,
                        ) { isSelected ->
                            Icon(
                                painter = painterResource(if (!isSelected) Res.drawable.moon else Res.drawable.check),
                                contentDescription = stringResource(Res.string.settings_theme_dark),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    label = {
                        Text(stringResource(Res.string.settings_theme_dark))
                    },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                )
            }

            Text(
                text = stringResource(Res.string.settings_section_tracking).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp)
            )

            val meterValues = SettingsState.DEFAULT_MINIMUM_MOVEMENT_METER_VALUES

            // Nearest default to the persisted value, so a value outside the list still maps to a step.
            val selectedIndex = remember(state.minimumMovementMeters) {
                val persisted = state.minimumMovementMeters ?: meterValues.first()
                meterValues.indices.minBy { abs(meterValues[it] - persisted) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.route),
                    contentDescription = null,
                )
                Column {
                    Text(
                        text = stringResource(Res.string.settings_minimum_movement_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(Res.string.settings_minimum_movement_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    SteppedSlider(
                        modifier = Modifier.padding(top = 8.dp),
                        stepCount = meterValues.size,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            onEvent(SettingsEvent.UpdateMinimumMovementMeters(meterValues[index]))
                        },
                        thumbLabel = { index -> HumanReadable.distance(meterValues[index], DistanceUnit.Meter) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.currentHomeserverUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onEvent(SettingsEvent.ToggleShowHomeserverInPersistentNotification(!state.showHomeserverInPersistentNotification)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.bell_ring),
                        contentDescription = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Zeige Homeserver in Benachrichtigung",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Zeigt deinen Homeserver (" + state.currentHomeserverUrl + ") in der Benachrichtigung an.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Checkbox(
                        checked = state.showHomeserverInPersistentNotification,
                        onCheckedChange = { onEvent(SettingsEvent.ToggleShowHomeserverInPersistentNotification(it)) },
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

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

@Composable
@Preview
private fun SettingsPreview() {
    SettingsContent(
        onBack = {},
        state = SettingsState(
            showLoginDialog = false,
            loginDialogHomeServerUrl = "https://trails.werkbank.space",
            hasLocationPermissions = true,

            appTheme = Theme.Light,
            minimumMovementMeters = 10,
        ),
        onEvent = {}
    )
}