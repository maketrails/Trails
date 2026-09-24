@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.page.shares.new_share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.BatteryMedium
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.ClockCounterClockwise
import com.phosphor.icons.regular.Link
import com.phosphor.icons.regular.Tag
import com.phosphor.icons.regular.Users
import com.phosphor.icons.regular.WarningCircle
import com.phosphor.icons.regular.X
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.model.User
import es.jvbabi.trails.page.home.bottomFadeOut
import es.jvbabi.trails.shareUrl
import es.jvbabi.trails.ui.components.DeviceImage
import es.jvbabi.trails.utils.PaddingValues
import es.jvbabi.trails.utils.padding
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import es.jvbabi.trails.utils.toDp
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import trails.app.shared.generated.resources.*
import kotlin.uuid.Uuid

@Composable
fun NewShareScreen(
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    close: () -> Unit,
) {

    val viewModel = koinViewModel<NewShareViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    NewShareContent(
        state = state,
        contentPadding = contentPadding,
        onEvent = viewModel::onEvent,
        nestedScrollConnection = nestedScrollConnection,
        close = close,
    )
}

@Composable
fun NewShareContent(
    contentPadding: PaddingValues,
    state: NewShareState,
    nestedScrollConnection: NestedScrollConnection?,
    onEvent: (event: NewShareEvent) -> Unit,
    close: () -> Unit,
) {
    if (state.currentDevice == null) return

    val localDensity = LocalDensity.current
    
    var currentFloatingSubmitButtonContainerHeight by remember { mutableStateOf(0.dp) }

    val localHapticFeedback = LocalHapticFeedback.current

    // Resolved up front rather than where it is used: the share sheet is also opened from a
    // LaunchedEffect, which cannot read resources itself.
    val shareSheetTitle = stringResource(
        Res.string.shares_share_sheet_title,
        (state.shareCreationState as? NewShareState.ShareCreationState.Success)?.username
            ?: stringResource(Res.string.common_unknown),
    )

    DisposableEffect(Unit) {
        onDispose {
            onEvent(NewShareEvent.ResetShareCreationState)
            onEvent(NewShareEvent.ResetInputFields)
        }
    }

    Column(
        modifier = Modifier
            .padding(contentPadding.copy(bottom = 0.dp))
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val titleFont = MaterialTheme.typography.headlineMedium
            Text(
                text = stringResource(Res.string.shares_new_title),
                style = titleFont,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .height(titleFont.lineHeight.toDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = close,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.X,
                        contentDescription = stringResource(Res.string.common_close)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, true)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .bottomFadeOut()
                    .let { if (nestedScrollConnection != null) it.nestedScroll(nestedScrollConnection) else it }
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = currentFloatingSubmitButtonContainerHeight + 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val bitmap = rememberBitmapFromBytes(state.image)
                    DeviceImage(
                        bitmap = bitmap,
                        modifier = Modifier.size(64.dp),
                    )

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                Res.string.devices_card_owner,
                                state.currentDevice.displayName,
                                state.currentDevice.owner.username,
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(
                                Res.string.devices_card_model,
                                state.currentDevice.friendlyName,
                                state.currentDevice.model,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        (state.currentDevice.batteryState as? Device.BatteryState.Shared)?.let {
                            Text(
                                text = stringResource(Res.string.common_percentage, HumanReadable.number(it.percentage)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(Res.string.shares_new_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.ClockCounterClockwise,
                        contentDescription = null,
                    )
                    Column {
                        Text(
                            text = stringResource(Res.string.shares_location_history),
                            style = MaterialTheme.typography.titleMedium
                        )
                        AnimatedContent(
                            modifier = Modifier.fillMaxWidth(),
                            targetState = state.selectedLocationShareHistoryState,
                            transitionSpec = { slideInVertically { it } togetherWith slideOutVertically { -it } }
                        ) { duration ->
                            Text(
                                text = stringResource(when (duration) {
                                    NewShareState.LocationShareHistoryState.NoHistory -> Res.string.shares_location_history_none
                                    NewShareState.LocationShareHistoryState.OneHour -> Res.string.shares_location_history_one_hour
                                    NewShareState.LocationShareHistoryState.SixHours -> Res.string.shares_location_history_six_hours
                                    NewShareState.LocationShareHistoryState.OneDay -> Res.string.shares_location_history_one_day
                                    NewShareState.LocationShareHistoryState.OneWeek -> Res.string.shares_location_history_one_week
                                    NewShareState.LocationShareHistoryState.Infinite -> Res.string.shares_location_history_infinite
                                }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Slider(
                            modifier = Modifier.padding(top = 8.dp),
                            value = state.selectedLocationShareHistoryState.ordinal.toFloat(),
                            onValueChange = {
                                onEvent(NewShareEvent.LocationShareHistoryStateChanged(NewShareState.LocationShareHistoryState.entries[it.toInt()]))
                                localHapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            },
                            valueRange = 0f..(NewShareState.LocationShareHistoryState.entries.size - 1).toFloat(),
                            steps = NewShareState.LocationShareHistoryState.entries.size - 2,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onEvent(NewShareEvent.ShareBatteryLevelChanged) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.BatteryMedium,
                        contentDescription = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.shares_share_battery_level),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Checkbox(
                        checked = state.shareBatteryLevel,
                        onCheckedChange = { onEvent(NewShareEvent.ShareBatteryLevelChanged) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.Tag,
                        contentDescription = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.shares_name_title),
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedTextField(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth(),
                            value = state.shareName,
                            onValueChange = { onEvent(NewShareEvent.ShareNameChanged(it)) },
                            placeholder = { Text(stringResource(Res.string.shares_name_placeholder)) },
                            singleLine = true,
                            isError = state.showShareNameEmptyError || state.showShareNameAlreadyUsedError,
                        )
                        AnimatedVisibility(
                            visible = state.showShareNameEmptyError,
                            enter = expandVertically(expandFrom = Alignment.CenterVertically),
                            exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(Res.string.shares_name_empty_error),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = state.showShareNameAlreadyUsedError,
                            enter = expandVertically(expandFrom = Alignment.CenterVertically),
                            exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(Res.string.shares_name_taken_error),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onEvent(NewShareEvent.AllowMultiuseLinkChanged) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.Users,
                        contentDescription = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.shares_allow_multiuse),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Checkbox(
                        checked = state.allowMultiuseLink,
                        onCheckedChange = { onEvent(NewShareEvent.AllowMultiuseLinkChanged) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { with(localDensity) { currentFloatingSubmitButtonContainerHeight = it.height.toDp() } }
                    .padding(8.dp)
            ) {
                Button(
                    onClick = { onEvent(NewShareEvent.CreateShareClicked) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = PhIcons.Regular.Link,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(Res.string.shares_share_link))
                    }
                }
            }
        }
    }

    if (state.shareCreationState != NewShareState.ShareCreationState.Idle) {
        AlertDialog(
            onDismissRequest = {
                val state = state.shareCreationState
                if (state is NewShareState.ShareCreationState.Loading) return@AlertDialog
                onEvent(NewShareEvent.ResetShareCreationState)
                if (state is NewShareState.ShareCreationState.Success) close()
            },
            icon = {
                AnimatedContent(
                    targetState = state.shareCreationState,
                ) { state ->
                    when (state) {
                        NewShareState.ShareCreationState.Idle -> {}
                        NewShareState.ShareCreationState.Loading -> LoadingIndicator()
                        is NewShareState.ShareCreationState.Success -> Icon(
                            imageVector = PhIcons.Regular.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        is NewShareState.ShareCreationState.Error -> Icon(
                            imageVector = PhIcons.Regular.WarningCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            title = {
                AnimatedContent(
                    modifier = Modifier.fillMaxWidth(),
                    targetState = state.shareCreationState,
                ) { creationState ->
                    Text(
                        text = when (creationState) {
                            NewShareState.ShareCreationState.Idle -> ""
                            NewShareState.ShareCreationState.Loading -> stringResource(Res.string.shares_creating)
                            is NewShareState.ShareCreationState.Success -> stringResource(Res.string.shares_created)
                            is NewShareState.ShareCreationState.Error -> stringResource(Res.string.common_error)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                AnimatedVisibility(
                    visible = state.shareCreationState is NewShareState.ShareCreationState.Success,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    TextButton(
                        onClick = {
                            if (state.shareCreationState !is NewShareState.ShareCreationState.Success) return@TextButton
                            shareUrl(
                                url = state.shareCreationState.url.buildString(),
                                title = shareSheetTitle,
                            )
                        }
                    ) {
                        Text(stringResource(Res.string.common_share))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val state = state.shareCreationState
                        onEvent(NewShareEvent.ResetShareCreationState)
                        if (state is NewShareState.ShareCreationState.Success) close()
                    },
                    enabled = state.shareCreationState != NewShareState.ShareCreationState.Loading && state.shareCreationState != NewShareState.ShareCreationState.Idle
                ) {
                    Text(stringResource(Res.string.common_close))
                }
            }
        )
    }

    LaunchedEffect(state.shareCreationState) {
        if (state.shareCreationState !is NewShareState.ShareCreationState.Success) return@LaunchedEffect
        shareUrl(
            url = state.shareCreationState.url.buildString(),
            title = shareSheetTitle,
        )
    }
}

@Composable
@Preview(showBackground = true)
fun NewShareScreenPreview() {
    NewShareContent(
        contentPadding = PaddingValues(),
        nestedScrollConnection = null,
        state = NewShareState(
            image = null,
            currentDevice = Device(
                id = Uuid.random(),
                manufacturer = "Google",
                model = "panther",
                friendlyName = "Pixel 7",
                displayName = "Google Pixel 7",
                batteryState = Device.BatteryState.Shared(
                    percentage = 49,
                    isCharging = false,
                ),
                owner = User(
                    id = Uuid.random(),
                    homeserver = "trails.werkbank.dev",
                    username = "testuser",
                )
            ),
            selectedLocationShareHistoryState = NewShareState.LocationShareHistoryState.OneHour,
            shareBatteryLevel = true,
            showShareNameEmptyError = true,
            shareCreationState = NewShareState.ShareCreationState.Idle,
        ),
        onEvent = {},
        close = {},
    )
}