@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.page.shares.add_share

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Clipboard
import com.phosphor.icons.regular.Link
import es.jvbabi.trails.getClipboardText
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import trails.app.shared.generated.resources.*

@Composable
fun AddShareScreen(
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<AddShareViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    AddScreenContent(
        onBack = onBack,
        state = state,
        onEvent = viewModel::onEvent,
    )

    LaunchedEffect(state.success) {
        if (state.success) onBack()
    }
}

@Composable
fun AddScreenContent(
    state: AddShareState,
    onEvent: (event: AddShareEvent) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onBack,
        icon = {
            Icon(
                imageVector = PhIcons.Regular.Link,
                contentDescription = null,
            )
        },
        title = {
            Text(stringResource(Res.string.shares_add_title))
        },
        text = {
            Column {
                TextField(
                    value = state.url,
                    onValueChange = { onEvent(AddShareEvent.UrlChanged(it)) },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.shares_add_url_placeholder),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboardText = getClipboardText()
                                if (!clipboardText.isNullOrBlank()) {
                                    onEvent(AddShareEvent.UrlChanged(clipboardText))
                                }
                            }
                        ) {
                            Icon(
                                imageVector = PhIcons.Regular.Clipboard,
                                contentDescription = stringResource(Res.string.common_paste),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            AnimatedContent(
                state.isLoading
            ) { isLoading ->
                if (isLoading) LoadingIndicator()
                else TextButton(
                    onClick = { onEvent(AddShareEvent.AddShare) }
                ) {
                    Text(stringResource(Res.string.common_continue))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onBack,
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )

    DisposableEffect(Unit) {
        focusRequester.requestFocus()

        onDispose {
            onEvent(AddShareEvent.Clear)
        }
    }
}

@Composable
@Preview
fun AddShareScreenPreview() {
    Scaffold {
        AddScreenContent(
            state = AddShareState(
                isLoading = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}