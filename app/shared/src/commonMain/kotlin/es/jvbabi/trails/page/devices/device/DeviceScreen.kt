@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.page.devices.device

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowLeft
import com.phosphor.icons.regular.BellRinging
import com.phosphor.icons.regular.PencilSimple
import com.phosphor.icons.regular.Trash
import com.phosphor.icons.regular.Vibrate
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.page.devices.Screen
import es.jvbabi.trails.ui.components.ConfigureTopBar
import es.jvbabi.trails.ui.components.TopBarAction
import es.jvbabi.trails.ui.components.TopBarActionDisplay
import es.jvbabi.trails.ui.components.DeviceImage
import es.jvbabi.trails.ui.components.LocalHazeState
import es.jvbabi.trails.utils.PaddingValues
import es.jvbabi.trails.utils.padding
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import trails.app.shared.generated.resources.*
import kotlin.uuid.Uuid

@Composable
fun DeviceScreen(
    deviceId: Uuid,
    contentPadding: PaddingValues,
    backstack: MutableList<Screen>,
    nestedScrollConnection: NestedScrollConnection,
) {
    val viewModel = koinViewModel<DeviceViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(deviceId) {
        viewModel.init(deviceId)
    }

    LaunchedEffect(state.deletionState) {
        if (state.deletionState is DeviceState.DeletionState.Success) {
            backstack.removeLastOrNull()
        }
    }

    // A returned share drops the device from the list, so this screen has to go too.
    LaunchedEffect(state.returnState) {
        if (state.returnState is DeviceState.ReturnState.Success) {
            backstack.removeLastOrNull()
        }
    }

    DeviceContent(
        state = state,
        contentPadding = contentPadding,
        onEvent = viewModel::onEvent,
        nestedScrollConnection = nestedScrollConnection,
        onBack = { backstack.removeLastOrNull() },
    )
}

@Composable
fun DeviceContent(
    state: DeviceState,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onEvent: (event: DeviceEvent) -> Unit,
    onBack: () -> Unit,
) {
    if (state.device == null) return

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showReturnDialog by rememberSaveable { mutableStateOf(false) }

    val isOwner = state.currentUser != null && state.currentUser.id == state.device.device.owner.id

    val hazeStyle = HazeMaterials.thin()

    ConfigureTopBar(
        title = state.device.device.displayName,
        subtitle = state.device.device.owner.username,
        navigationIcon = remember { {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .hazeEffect(LocalHazeState.current) {
                        blurEffect {
                            blurRadius = 8.dp
                            style = hazeStyle
                        }
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
            ) {
                Icon(
                    imageVector = PhIcons.Regular.ArrowLeft,
                    contentDescription = stringResource(Res.string.common_back),
                    modifier = Modifier.size(24.dp),
                )
            }
        } },
        actions = when {
            isOwner -> listOf(
                TopBarAction(
                    title = stringResource(Res.string.device_rename),
                    icon = PhIcons.Regular.PencilSimple,
                    display = TopBarActionDisplay.ALWAYS,
                    onClick = { showRenameDialog = true },
                ),
                TopBarAction(
                    title = stringResource(Res.string.common_delete),
                    icon = PhIcons.Regular.Trash,
                    destructive = true,
                    onClick = { showDeleteDialog = true },
                ),
            )

            // A foreign device is only visible through a share, which we can give back.
            state.shares.isNotEmpty() -> listOf(
                TopBarAction(
                    title = stringResource(Res.string.device_return_share),
                    icon = PhIcons.Regular.Trash,
                    display = TopBarActionDisplay.ALWAYS,
                    destructive = true,
                    onClick = { showReturnDialog = true },
                ),
            )

            else -> emptyList()
        },
    )

    if (showRenameDialog) RenameDeviceDialog(
        device = state.device.device,
        renameState = state.renameState,
        onDismiss = { showRenameDialog = false },
        onConfirm = { onEvent(DeviceEvent.Rename(it)) },
    )

    // Close the rename dialog once the server confirms the change.
    LaunchedEffect(state.renameState) {
        if (state.renameState is DeviceState.RenameState.Success) showRenameDialog = false
    }

    if (showReturnDialog) ReturnShareDialog(
        returnState = state.returnState,
        onDismiss = { showReturnDialog = false },
        onConfirm = { onEvent(DeviceEvent.ReturnShare) },
    )

    if (showDeleteDialog) AlertDialog(
        onDismissRequest = { showDeleteDialog = false },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(DeviceEvent.Delete)
                },
                enabled = state.deletionState !is DeviceState.DeletionState.Loading
            ) {
                Text(
                    text = stringResource(Res.string.common_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { showDeleteDialog = false },
                enabled = state.deletionState !is DeviceState.DeletionState.Loading
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        icon = {
            AnimatedContent(
                targetState = state.deletionState is DeviceState.DeletionState.Loading
            ) { isLoading ->
                if (isLoading) LoadingIndicator(Modifier.size(24.dp))
                else Icon(
                    imageVector = PhIcons.Regular.Trash,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                    contentDescription = null,
                )
            }
        },
        title = {
            Text(stringResource(Res.string.device_delete_title))
        },
        text = {
            Column {
                Text(stringResource(Res.string.device_delete_message))
                var errorMessage by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(state.deletionState) {
                    if (state.deletionState is DeviceState.DeletionState.Error) {
                        errorMessage = state.deletionState.message
                    }
                }

                AnimatedVisibility(
                    visible = state.deletionState is DeviceState.DeletionState.Error,
                    enter = expandVertically(expandFrom = Alignment.CenterVertically),
                    exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(Res.string.common_error_with_message, errorMessage.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 6,
                    )
                }
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(LocalHazeState.current)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding.copy(bottom = 0.dp))
                .padding(top = 64.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    val bitmap = rememberBitmapFromBytes(state.image)
                    DeviceImage(
                        bitmap = bitmap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .aspectRatio(1f),
                        shape = MaterialShapes.Cookie12Sided.toShape(),
                        imageFillFraction = .55f,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                Box(Modifier.weight(1f)) {
                    Column {
                        if (state.pingState != null) Button(
                            onClick = { onEvent(DeviceEvent.Ping) },
                            enabled = state.pingState == DeviceState.PingState.Ready
                        ) {
                            AnimatedContent(
                                targetState = state.pingState == DeviceState.PingState.Loading,
                            ) { isLoading ->
                                if (isLoading) LoadingIndicator(Modifier.size(16.dp))
                                else Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = PhIcons.Regular.BellRinging,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(stringResource(Res.string.device_ping))
                                }
                            }
                        }

                        if (state.ringState != null && state.ringState != DeviceState.RingState.Disabled) {
                            if (state.ringState == DeviceState.RingState.Ringing) {
                                Button(
                                    onClick = { onEvent(DeviceEvent.StopRing) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = PhIcons.Regular.Vibrate,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(stringResource(Res.string.device_ring_stop))
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { onEvent(DeviceEvent.Ring) },
                                    enabled = state.ringState == DeviceState.RingState.Ready,
                                ) {
                                    AnimatedContent(
                                        targetState = state.ringState == DeviceState.RingState.Loading,
                                    ) { isLoading ->
                                        if (isLoading) LoadingIndicator(Modifier.size(16.dp))
                                        else Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = PhIcons.Regular.Vibrate,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(stringResource(Res.string.device_ring))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReturnShareDialog(
    returnState: DeviceState.ReturnState?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isLoading = returnState is DeviceState.ReturnState.Loading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isLoading,
            ) {
                Text(
                    text = stringResource(Res.string.device_return_share_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        icon = {
            AnimatedContent(targetState = isLoading) { loading ->
                if (loading) LoadingIndicator(Modifier.size(24.dp))
                else Icon(
                    imageVector = PhIcons.Regular.Trash,
                    tint = MaterialTheme.colorScheme.error,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        title = {
            Text(stringResource(Res.string.device_return_share_title))
        },
        text = {
            Column {
                Text(stringResource(Res.string.device_return_share_message))

                var errorMessage by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(returnState) {
                    if (returnState is DeviceState.ReturnState.Error) {
                        errorMessage = returnState.message
                    }
                }

                AnimatedVisibility(
                    visible = returnState is DeviceState.ReturnState.Error,
                    enter = expandVertically(expandFrom = Alignment.CenterVertically),
                    exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(Res.string.common_error_with_message, errorMessage.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 6,
                    )
                }
            }
        }
    )
}

@Composable
private fun RenameDeviceDialog(
    device: Device,
    renameState: DeviceState.RenameState?,
    onDismiss: () -> Unit,
    onConfirm: (customName: String?) -> Unit,
) {
    val defaultName = stringResource(Res.string.device_default_name, device.manufacturer, device.friendlyName)
    val hasCustomName = device.displayName != defaultName
    // An empty field clears the custom name; the server then reverts to the
    // model name, so we seed it empty when no custom name is set.
    var name by rememberSaveable(device.id) {
        mutableStateOf(if (hasCustomName) device.displayName else "")
    }
    val isLoading = renameState is DeviceState.RenameState.Loading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifEmpty { null }) },
                enabled = !isLoading,
            ) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        icon = {
            AnimatedContent(targetState = isLoading) { loading ->
                if (loading) LoadingIndicator(Modifier.size(24.dp))
                else Icon(
                    imageVector = PhIcons.Regular.PencilSimple,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        title = {
            Text(stringResource(Res.string.device_rename_title))
        },
        text = {
            Column {
                Text(stringResource(Res.string.device_rename_message))

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !isLoading,
                    placeholder = { Text(defaultName) },
                    modifier = Modifier.fillMaxWidth(),
                )

                var errorMessage by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(renameState) {
                    if (renameState is DeviceState.RenameState.Error) {
                        errorMessage = renameState.message
                    }
                }

                AnimatedVisibility(
                    visible = renameState is DeviceState.RenameState.Error,
                    enter = expandVertically(expandFrom = Alignment.CenterVertically),
                    exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(Res.string.common_error_with_message, errorMessage.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 6,
                    )
                }
            }
        }
    )
}