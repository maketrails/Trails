package es.jvbabi.trails.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowLeft
import com.phosphor.icons.regular.DotsThreeVertical
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import es.jvbabi.trails.ThemeWrapper
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*

@Composable
fun TopBar(
    state: TopBarState,
    modifier: Modifier = Modifier,
) {
    val localDensity = LocalDensity.current
    var actionsWidth by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
    ) {
        // How much room the actions may take before collapsing into the overflow
        // menu. The title is centered with symmetric padding, so keeping it
        // legible caps the actions at half of the width left beside it.
        val actionsBudget = if (state.config.title.isNotEmpty()) {
            ((maxWidth - MinCenteredTitleWidth) * 0.5f).coerceAtLeast(0.dp)
        } else {
            val navWidth = if (state.config.navigationIcon != null) 48.dp else 0.dp
            (maxWidth - navWidth).coerceAtLeast(0.dp)
        }

        AnimatedContent(
            targetState = state.config.navigationIcon,
            transitionSpec = { fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut() },
            modifier = Modifier.align(Alignment.CenterStart)
        ) { content ->
            content?.invoke()
        }

        val titleStartPaddingTarget = if (state.config.navigationIcon != null) 48.dp else 8.dp
        val titleEndPaddingTarget = actionsWidth.coerceAtLeast(8.dp)

        val titleHorizontalPadding by animateDpAsState(
            targetValue = max(titleStartPaddingTarget, titleEndPaddingTarget),
            label = "Title horizontal padding",
            animationSpec = spring()
        )

        AnimatedVisibility(
            visible = state.config.title.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 4.dp)
        ) {
            val hazeStyle = HazeMaterials.thin()
            Column(
                modifier = Modifier
                    .padding(horizontal = titleHorizontalPadding)
                    .clip(RoundedCornerShape(50))
                    .hazeEffect(LocalHazeState.current) {
                        blurEffect {
                            blurRadius = 8.dp
                            style = hazeStyle
                        }
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                    .padding(vertical = 4.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = state.config.title,
                    transitionSpec = { fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut() },
                ) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                AnimatedContent(
                    targetState = state.config.subtitle,
                    transitionSpec = { fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut() },
                ) { subtitle ->
                    if (subtitle != null) Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.CenterEnd).animateContentSize(spring())) {
            Row(
                modifier = Modifier.onSizeChanged { size ->
                    with(localDensity) {
                        actionsWidth = size.width.toDp()
                    }
                }
            ) {
                CompositionLocalProvider(LocalTopBarActionsMaxWidth provides actionsBudget) {
                    state.config.actions(this@Row)
                }
            }
        }
    }
}

data class TopBarConfig(
    val title: String = "",
    val subtitle: String? = null,
    val navigationIcon: (@Composable () -> Unit)? = null,
    val actions: @Composable RowScope.() -> Unit = {},
)

/** How a [TopBarAction] competes for a spot in the bar versus the overflow menu. */
enum class TopBarActionDisplay {
    /** Always a dedicated icon button, even when space is tight. */
    ALWAYS,

    /** An icon button when it fits; otherwise it collapses into the overflow menu. */
    IF_ROOM,

    /** Always in the three-dot overflow menu. */
    NEVER,
}

/**
 * A single top-bar action. Callers describe a flat list; the bar measures the
 * available width and renders as many [IF_ROOM][TopBarActionDisplay.IF_ROOM]
 * actions as fit, moving the rest into the three-dot overflow menu.
 */
data class TopBarAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val display: TopBarActionDisplay = TopBarActionDisplay.IF_ROOM,
    val destructive: Boolean = false,
)

/** Layout footprint of a single action icon button, used to measure the fit. */
private val TopBarActionSize = 48.dp
private val TopBarActionSpacing = 8.dp
/** Space kept for the centered title before actions start collapsing. */
private val MinCenteredTitleWidth = 96.dp

/**
 * Max width the actions may occupy in the current bar, provided by [TopBar]
 * after measuring. Defaults to unbounded so actions render even without a bar.
 */
val LocalTopBarActionsMaxWidth = compositionLocalOf { Dp.Infinity }

/** The circular, blurred background shared by the navigation icon and actions. */
@Composable
private fun topBarActionBackground(): Modifier {
    val hazeState = LocalHazeState.current
    val hazeStyle = HazeMaterials.thin()
    return Modifier
        .clip(CircleShape)
        .hazeEffect(hazeState) {
            blurEffect {
                blurRadius = 8.dp
                style = hazeStyle
            }
        }
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
}

/** Width needed to lay out [count] action buttons with spacing between them. */
private fun actionsRowWidth(count: Int): Dp =
    if (count <= 0) 0.dp else TopBarActionSize * count + TopBarActionSpacing * (count - 1)

/**
 * Renders [actions] as icon buttons plus a three-dot overflow menu. The split is
 * measured against [LocalTopBarActionsMaxWidth]: pinned ([ALWAYS][TopBarActionDisplay.ALWAYS])
 * actions always show, [IF_ROOM][TopBarActionDisplay.IF_ROOM] ones fill the
 * remaining space in order, and whatever is left overflows into the menu.
 */
@Composable
fun TopBarActions(actions: List<TopBarAction>) {
    val available = LocalTopBarActionsMaxWidth.current

    val always = actions.filter { it.display == TopBarActionDisplay.ALWAYS }
    val ifRoom = actions.filter { it.display == TopBarActionDisplay.IF_ROOM }
    val never = actions.filter { it.display == TopBarActionDisplay.NEVER }

    // Everything can be a button only when nothing is forced into the menu and
    // the whole row fits.
    val fitsWithoutMenu = never.isEmpty() &&
            actionsRowWidth(always.size + ifRoom.size) <= available

    val barActions: List<TopBarAction>
    val menuActions: List<TopBarAction>
    if (fitsWithoutMenu) {
        barActions = always + ifRoom
        menuActions = emptyList()
    } else {
        // The overflow button will be shown, so reserve a slot for it (the +1)
        // and fit as many IF_ROOM actions as the remaining width allows.
        var visible = 0
        while (
            visible < ifRoom.size &&
            actionsRowWidth(always.size + (visible + 1) + 1) <= available
        ) {
            visible++
        }
        barActions = always + ifRoom.take(visible)
        menuActions = ifRoom.drop(visible) + never
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TopBarActionSpacing),
    ) {
        barActions.forEach { action ->
            IconButton(
                onClick = action.onClick,
                modifier = topBarActionBackground().size(TopBarActionSize),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.title,
                    tint = if (action.destructive) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        if (menuActions.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = topBarActionBackground().size(TopBarActionSize),
                ) {
                    Icon(
                        imageVector = PhIcons.Regular.DotsThreeVertical,
                        contentDescription = stringResource(Res.string.common_more_actions),
                        modifier = Modifier.size(24.dp),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    menuActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.title) },
                            onClick = {
                                expanded = false
                                action.onClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            colors = if (action.destructive) MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error,
                            ) else MenuDefaults.itemColors(),
                        )
                    }
                }
            }
        }
    }
}

@Stable
class TopBarState {
    private data class Entry(val key: Any, val config: TopBarConfig)

    private val stack = mutableStateListOf<Entry>()

    val config: TopBarConfig
        get() = stack.lastOrNull()?.config ?: TopBarConfig()

    internal fun push(key: Any, config: TopBarConfig) {
        val i = stack.indexOfFirst { it.key == key }
        if (i >= 0) stack[i] = Entry(key, config) else stack.add(Entry(key, config))
    }

    internal fun pop(key: Any) {
        stack.removeAll { it.key == key }
    }
}

val LocalTopBar = staticCompositionLocalOf<TopBarState> {
    error("LocalTopBar not provided — wrap your app with CompositionLocalProvider")
}

val LocalHazeState = staticCompositionLocalOf<HazeState> {
    error("HazeState not supplied")
}

@Composable
fun ConfigureTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val topBar = LocalTopBar.current
    val key = remember { Any() }

    // Update on every recomposition (e.g. state-driven title changes)
    SideEffect {
        topBar.push(key, TopBarConfig(title, subtitle, navigationIcon, actions))
    }
    // Clean up when screen leaves composition
    DisposableEffect(Unit) {
        onDispose { topBar.pop(key) }
    }
}

/**
 * Declarative variant of [ConfigureTopBar]: pass a flat list of [TopBarAction]s
 * and the bar renders icon buttons plus a three-dot overflow menu for you.
 */
@Composable
fun ConfigureTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: List<TopBarAction>,
) {
    ConfigureTopBar(
        title = title,
        subtitle = subtitle,
        navigationIcon = navigationIcon,
        actions = { TopBarActions(actions) },
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun TopBarTitleOnlyPreview() {
    TopBar(
        state = remember {
            TopBarState().apply {
                push("key", TopBarConfig(title = "Devices"))
            }
        }
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun TopBarWithSubtitlePreview() {
    TopBar(
        state = remember {
            TopBarState().apply {
                push("key", TopBarConfig(title = "Devices", subtitle = "3 connected"))
            }
        }
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun TopBarWithBackButtonPreview() {
    TopBar(
        state = remember {
            TopBarState().apply {
                push(
                    "key", TopBarConfig(
                        title = "Device A1B2",
                        subtitle = "Online",
                        navigationIcon = {
                            IconButton(onClick = {}) {
                                Icon(imageVector = PhIcons.Regular.ArrowLeft, contentDescription = stringResource(Res.string.common_back))
                            }
                        },
                    )
                )
            }
        }
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun TopBarLongTextPreview() {
    TopBar(
        state = remember {
            TopBarState().apply {
                push(
                    "key", TopBarConfig(
                        title = "This Is A Very Long Title That Should Ellipsize",
                        subtitle = "And this subtitle is also unreasonably long for any normal screen",
                        navigationIcon = {
                            IconButton(onClick = {}) {
                                Icon(imageVector = PhIcons.Regular.ArrowLeft, contentDescription = stringResource(Res.string.common_back))
                            }
                        },
                    )
                )
            }
        }
    )
}

@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun TopBarEmptyPreview() {
    TopBar(state = remember { TopBarState() })
}