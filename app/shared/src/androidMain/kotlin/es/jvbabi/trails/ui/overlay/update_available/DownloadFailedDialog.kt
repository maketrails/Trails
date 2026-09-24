package es.jvbabi.trails.ui.overlay.update_available

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.WarningCircle
import es.jvbabi.trails.ThemeWrapper
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.Res
import trails.app.shared.generated.resources.common_ok
import trails.app.shared.generated.resources.update_download_failed_message
import trails.app.shared.generated.resources.update_download_failed_title

/**
 * Says that the download did not work, and nothing more.
 *
 * No cause and no retry button: the reasons all read the same to a user (offline, rate limited,
 * nowhere to write to), and "Install" is still sitting right behind this dialog for a second attempt.
 */
@Composable
fun DownloadFailedDialog(
    onEvent: (event: UpdateAvailableEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(UpdateAvailableEvent.DismissDownloadError) },
        icon = {
            Icon(
                imageVector = PhIcons.Regular.WarningCircle,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(Res.string.update_download_failed_title)) },
        text = { Text(stringResource(Res.string.update_download_failed_message)) },
        confirmButton = {
            TextButton(onClick = { onEvent(UpdateAvailableEvent.DismissDownloadError) }) {
                Text(stringResource(Res.string.common_ok))
            }
        },
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun DownloadFailedDialogPreview() {
    DownloadFailedDialog(onEvent = {})
}
