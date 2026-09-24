@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.page.shares.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Plus
import com.phosphor.icons.regular.ShareNetwork
import es.jvbabi.trails.page.home.bottomFadeOut
import es.jvbabi.trails.page.shares.SharesScreen
import es.jvbabi.trails.page.shares.add_share.AddShareScreen
import es.jvbabi.trails.page.shares.new_share.NewShareScreen
import es.jvbabi.trails.utils.PaddingValues
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*

@Composable
fun SharesScreen(
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onExpandCard: () -> Unit,
    onSemiExpandCard: () -> Unit,
) {

    val backstack = remember { mutableStateListOf<SharesScreen>(SharesScreen.Main) }

    key(contentPadding, nestedScrollConnection) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = backstack,
            onBack = { backstack.removeLastOrNull() },
            entryProvider = { key ->
                return@NavDisplay when (key) {
                    is SharesScreen.Main -> NavEntry(key = key) {
                        SharesContent(
                            nestedScrollConnection = nestedScrollConnection,
                            onOpenNewShare = {
                                onExpandCard()
                                backstack.add(SharesScreen.NewShare)
                            },
                        )
                    }

                    is SharesScreen.NewShare -> NavEntry(key = key) {
                        NewShareScreen(
                            contentPadding = contentPadding,
                            nestedScrollConnection = nestedScrollConnection,
                            close = {
                                backstack.removeLastOrNull()
                                onSemiExpandCard()
                            },
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun SharesContent(
    nestedScrollConnection: NestedScrollConnection?,
    onOpenNewShare: () -> Unit,
) {
    var showAddShareDialog by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .bottomFadeOut()
                .let { if (nestedScrollConnection != null) it.nestedScroll(nestedScrollConnection) else it }
                .verticalScroll(rememberScrollState())
        ) {
        }

        Column(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.BottomEnd),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingActionButton(
                onClick = { showAddShareDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    imageVector = PhIcons.Regular.Plus,
                    contentDescription = stringResource(Res.string.shares_enter_link),
                    modifier = Modifier.size(24.dp),
                )
            }
            MediumFloatingActionButton(
                onClick = onOpenNewShare,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = PhIcons.Regular.ShareNetwork,
                    contentDescription = stringResource(Res.string.shares_add),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    if (showAddShareDialog) AddShareScreen(
        onBack = { showAddShareDialog = false },
    )
}

@Preview
@Composable
fun SharesScreenPreview() {
    SharesContent(
        nestedScrollConnection = null,
        onOpenNewShare = {},
    )
}
