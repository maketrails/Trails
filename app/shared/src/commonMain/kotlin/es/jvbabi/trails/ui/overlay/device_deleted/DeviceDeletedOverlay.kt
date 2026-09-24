@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.ui.overlay.device_deleted

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowRight
import com.phosphor.icons.regular.Trash
import es.jvbabi.trails.ui.components.DeviceImage
import es.jvbabi.trails.ThemeWrapper
import es.jvbabi.trails.domain.model.Device
import es.jvbabi.trails.domain.model.User
import es.jvbabi.trails.utils.rememberBitmapFromBytes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import trails.app.shared.generated.resources.*
import kotlin.uuid.Uuid

@Composable
fun DeviceDeletedOverlay() {

    val viewModel = koinViewModel<DeviceDeletedViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it == SheetValue.Expanded || state?.isDismissed == true }
    )

    LaunchedEffect(state?.isDismissed) {
        if (state?.isDismissed == true) sheetState.hide()
    }

    if (state != null) ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { viewModel.onEvent(DeviceDeletedEvent.Dismissed) },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = false
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            DeviceDeletedContent(
                onEvent = viewModel::onEvent,
                state = state!!,
            )
        }
    }
}

@Composable
fun DeviceDeletedContent(
    onEvent: (event: DeviceDeletedEvent) -> Unit,
    state: DeviceDeletedState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(Modifier.weight(.5f))
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val bitmap = rememberBitmapFromBytes(state.image)
            DeviceImage(
                bitmap = bitmap,
                modifier = Modifier.size(108.dp),
                shape = MaterialShapes.Cookie12Sided.toShape(),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Icon(
                imageVector = PhIcons.Regular.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )

            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(MaterialShapes.Cookie6Sided.toShape())
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhIcons.Regular.Trash,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(.4f))
        Text(
            text = stringResource(Res.string.device_deleted_title),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.device_deleted_message, state.deletedByDevice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1.25f))
        Button(
            onClick = { onEvent(DeviceDeletedEvent.RequestDismiss) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(Res.string.common_ok))
        }
    }
}

@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun DeviceDeletedPreview() {
    DeviceDeletedContent(
        onEvent = {},
        state = DeviceDeletedState(
            device = Device(
                id = Uuid.random(),
                manufacturer = "Google",
                model = "panther",
                friendlyName = "Pixel 7",
                displayName = "Google Pixel 7",
                owner = User(
                    id = Uuid.random(),
                    homeserver = "trailsdevelopment.jvbabi.es",
                    username = "test"
                ),
                batteryState = Device.BatteryState.NotShared,
            ),
            deletedByDevice = "iPhone 12",
            image = null,
        )
    )
}