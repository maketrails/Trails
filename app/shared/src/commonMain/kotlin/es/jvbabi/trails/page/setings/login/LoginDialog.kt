package es.jvbabi.trails.page.setings.login

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import es.jvbabi.trails.utils.HttpsVisualTransformation
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.Res
import trails.app.shared.generated.resources.common_ok
import trails.app.shared.generated.resources.settings_login
import trails.app.shared.generated.resources.settings_login_homeserver_label

@Composable
fun LoginDialog(
    url: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    onValueChange: (to: String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_login)) },
        text = {
            Column {
                TextField(
                    visualTransformation = HttpsVisualTransformation(MaterialTheme.colorScheme.outline),
                    value = url,
                    onValueChange = { onValueChange(it) },
                    label = { Text(stringResource(Res.string.settings_login_homeserver_label)) },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            Button(onClick = onSubmit) {
                Text(stringResource(Res.string.common_ok))
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Preview
@Composable
private fun LoginDialogPreview() {
    LoginDialog(
        url = "trails.example.com",
        onDismiss = {},
        onSubmit = {},
        onValueChange = {},
    )
}