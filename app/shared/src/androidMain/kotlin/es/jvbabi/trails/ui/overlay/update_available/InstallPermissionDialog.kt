package es.jvbabi.trails.ui.overlay.update_available

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.WarningCircle
import es.jvbabi.trails.ThemeWrapper
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.Res
import trails.app.shared.generated.resources.update_install_permission_always_manual
import trails.app.shared.generated.resources.update_install_permission_grant
import trails.app.shared.generated.resources.update_install_permission_manual
import trails.app.shared.generated.resources.update_install_permission_message
import trails.app.shared.generated.resources.update_install_permission_title

/**
 * Tells the user that the app may not install the update, and offers the ways out of that.
 *
 * The three actions are stacked rather than laid out in a row: they are alternatives of equal
 * standing, not a confirm/cancel pair, and "Always install manually" is far too long to sit beside
 * anything.
 */
@Composable
fun InstallPermissionDialog(
    onEvent: (event: UpdateAvailableEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(UpdateAvailableEvent.DismissInstallPermission) },
        icon = {
            Icon(
                imageVector = PhIcons.Regular.WarningCircle,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(Res.string.update_install_permission_title)) },
        text = { Text(stringResource(Res.string.update_install_permission_message)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                TextButton(onClick = { onEvent(UpdateAvailableEvent.GrantInstallPermission) }) {
                    Text(stringResource(Res.string.update_install_permission_grant))
                }
                TextButton(onClick = { onEvent(UpdateAvailableEvent.InstallManually) }) {
                    Text(stringResource(Res.string.update_install_permission_manual))
                }
                TextButton(onClick = { onEvent(UpdateAvailableEvent.AlwaysInstallManually) }) {
                    Text(stringResource(Res.string.update_install_permission_always_manual))
                }
            }
        },
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun InstallPermissionDialogPreview() {
    InstallPermissionDialog(onEvent = {})
}
