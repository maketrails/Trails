@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.trails.ui.overlay.update_available

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowRight
import com.phosphor.icons.regular.X
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.trails.ThemeWrapper
import es.jvbabi.trails.domain.model.*
import es.jvbabi.trails.ui.components.ProgressiveBlurScrim
import es.jvbabi.trails.ui.components.ScrimEdge
import es.jvbabi.trails.utils.blendColor
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import trails.app.shared.generated.resources.*
import java.text.NumberFormat
import java.util.Locale as JavaLocale

@Composable
actual fun UpdateAvailableOverlay() {
    val viewModel = koinViewModel<UpdateAvailableViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Nothing is held back here: swiping the sheet down and pressing back both mean "not now", which
    // is exactly what the button next to "Install" says.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Dismissing takes two steps. The sheet animates itself out first, and only once it is gone does
    // the overlay leave the composition — leaving any earlier cuts the animation off, staying any
    // longer leaves the scrim swallowing every touch meant for the screen behind it.
    LaunchedEffect(state?.isDismissed) {
        if (state?.isDismissed != true) return@LaunchedEffect
        sheetState.hide()
        viewModel.onEvent(UpdateAvailableEvent.Dismissed)
    }

    if (state != null) ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { viewModel.onEvent(UpdateAvailableEvent.RequestDismiss) },
    ) {
        UpdateAvailableOverlayContent(
            onEvent = viewModel::onEvent,
            state = state!!,
        )
    }

    // A sibling of the sheet rather than part of its content: a dialog brings its own window, so it
    // sits above the sheet either way, and the sheet stays untouched behind it.
    if (state?.isInstallPermissionRequired == true) {
        // The permission is granted in the system settings, which means leaving the app, and Android
        // reports nothing back when it is done. So the dialog asks again every time the app resumes
        // while it is up — tied to the lifecycle rather than to the app's own foreground flag, which
        // is driven by the activity by hand and is not this precise about what "back in front" is.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.onEvent(UpdateAvailableEvent.RecheckInstallPermission)
        }

        InstallPermissionDialog(onEvent = viewModel::onEvent)
    }

    if (state?.download is UpdateDownload.Failed) {
        DownloadFailedDialog(onEvent = viewModel::onEvent)
    }
}

@Composable
fun UpdateAvailableOverlayContent(
    onEvent: (event: UpdateAvailableEvent) -> Unit,
    state: UpdateAvailableState,
) {
    // A version without a single entry has nothing to show, and neither has a changelog that only
    // consists of those - in that case the overlay stays exactly the prompt it was.
    val versions = state.changelog?.versions?.filterNot { it.isEmpty }.orEmpty()

    val phase = when {
        state.areChangelogsLoading -> ChangelogPhase.Loading
        versions.isNotEmpty() -> ChangelogPhase.Present
        else -> ChangelogPhase.Absent
    }

    val density = LocalDensity.current
    val hazeState = rememberHazeState()

    // Measured rather than guessed: the buttons float above the changelog, so the list needs to know
    // how much room to leave below its last entry. A hard-coded value would break as soon as the
    // button text wraps or the font scale changes.
    var buttonsHeight by remember { mutableStateOf(0.dp) }

    // 0 while the prompt still has the sheet to itself, 1 once the changelog has taken over the
    // slack it was centring itself in. The prompt is the same either way, only higher up, so it
    // travels there rather than being faded through or snapped into place.
    val slackTaken by animateFloatAsState(
        targetValue = if (phase == ChangelogPhase.Present) 1f else 0f,
        label = "changelogSlack",
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                // No bottom padding: a changelog long enough to need it runs to the very bottom of
                // the sheet and under the scrim, which brings its own padding.
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.weight(promptSlack(.5f, slackTaken)))
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Image(
                    painter = painterResource(Res.drawable.app_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .size(108.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.weight(promptSlack(.4f, slackTaken)))
            Text(
                text = stringResource(Res.string.update_title),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.update_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = AppVersions.tagOf(state.currentVersion),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Right,
                )

                Icon(
                    imageVector = PhIcons.Regular.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )

                AnimatedContent(
                    targetState = state.latestVersion,
                    modifier = Modifier.weight(1f),
                ) { latestVersion ->
                    if (latestVersion != null) {
                        Text(
                            text = AppVersions.tagOf(latestVersion),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Left,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // Only what the changelog has to say is traded here - the prompt above is the same in
            // every phase and must not be faded through with it. The room is the same in every
            // phase too, so a phase carries no height of its own: it is the weights above that give
            // way, and the content is top aligned in whatever is left.
            //
            // The phase rather than a plain flag is the target state so the outgoing side keeps
            // showing what it had - the loading hint fades out instead of vanishing the instant the
            // changelog lands.
            AnimatedContent(
                targetState = phase,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "changelogReveal",
            ) { currentPhase ->
                when (currentPhase) {
                    ChangelogPhase.Present -> ChangelogSlot(
                        versions = versions,
                        onIssueClick = { issue ->
                            onEvent(UpdateAvailableEvent.OpenIssue(issueUrl(issue)))
                        },
                        buttonsHeight = buttonsHeight,
                        modifier = Modifier.fillMaxSize(),
                    )

                    ChangelogPhase.Loading -> ChangelogLoadingPlaceholder(Modifier.fillMaxWidth())

                    // Nothing to say and nothing coming: the prompt keeps the sheet to itself.
                    ChangelogPhase.Absent -> Spacer(Modifier.fillMaxSize())
                }
            }
        }

        ProgressiveBlurScrim(
            hazeState = hazeState,
            edge = ScrimEdge.Bottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    buttonsHeight = with(density) { size.height.toDp() }
                },
        ) {
            val isDownloading = state.download is UpdateDownload.Running

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                val spacing = 8.dp
                val height = ButtonDefaults.MinHeight            // 40.dp
                val secondaryWidth by animateDpAsState(
                    targetValue = if (isDownloading) 48.dp else (maxWidth - spacing) / 2,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "secondaryWidth",
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = isDownloading,
                        transitionSpec = { fadeIn() togetherWith fadeOut() using null },
                        modifier = Modifier.width(secondaryWidth),
                        label = "secondary",
                    ) { downloading ->
                        if (downloading) {
                            OutlinedIconButton(
                                onClick = { onEvent(UpdateAvailableEvent.CancelDownload) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height),
                                shape = ButtonDefaults.outlinedShape,
                                colors = IconButtonDefaults.outlinedIconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            ) {
                                Icon(
                                    imageVector = PhIcons.Regular.X,
                                    contentDescription = stringResource(Res.string.update_cancel_download),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onEvent(UpdateAvailableEvent.RequestDismiss) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.update_not_now),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                )
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = isDownloading,
                        transitionSpec = { fadeIn() togetherWith fadeOut() using null },
                        modifier = Modifier.weight(1f),
                        label = "secondary",
                    ) { downloading ->
                        if (downloading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height)
                                    .clip(ButtonDefaults.shape)
                                    .background(ButtonDefaults.buttonColors().containerColor),
                            ) {
                                if (state.download is UpdateDownload.Running) {
                                    val progress = state.download.progress
                                    if (progress != null) {
                                        val settledProgress by animateFloatAsState(
                                            targetValue = progress,
                                            label = "downloadProgress",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .background(blendColor(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, .8f))
                                                .fillMaxHeight()
                                                .fillMaxWidth(settledProgress)
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(vertical = 4.dp, horizontal = 16.dp),
                                    ) {
                                        Text(
                                            text = "Wird heruntergeladen",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.wrapContentWidth(unbounded = true),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Text(
                                            text = if (state.download.totalBytes != null && progress != null) {
                                                stringResource(
                                                    Res.string.update_downloading_progress,
                                                    HumanReadable.fileSize(state.download.downloadedBytes),
                                                    HumanReadable.fileSize(state.download.totalBytes),
                                                    percentage(progress),
                                                )
                                            } else {
                                                stringResource(Res.string.update_downloading)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.wrapContentWidth(unbounded = true),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = { onEvent(UpdateAvailableEvent.Install) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height),
                            ) {
                                Text(text = stringResource(Res.string.update_install), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [fraction] as a percentage, in the locale Compose renders in.
 *
 * That is the same locale Human-Readable is kept in step with (see `SyncHumanReadableLocale`), so
 * both halves of the progress line agree on how they format numbers.
 *
 * Deliberately not part of the translated format string: where the percent sign goes and whether a
 * space precedes it differs by language ("26%" against "26 %"), and a number format already knows
 * that for every locale — a translation would only get the chance to disagree with it.
 */
@Composable
private fun percentage(fraction: Float): String {
    val languageTag = Locale.current.toLanguageTag()
    val format = remember(languageTag) {
        NumberFormat.getPercentInstance(JavaLocale.forLanguageTag(languageTag))
    }
    return format.format(fraction)
}

/**
 * Weight of a piece of the slack the prompt centres itself in, given [taken] of it to the changelog.
 *
 * Never quite reaches zero: Compose rejects a weight of zero outright, and a share this small is
 * worth less than a pixel of the sheet, so it reads as gone.
 */
private fun promptSlack(weight: Float, taken: Float): Float = lerp(weight, MIN_SLACK_WEIGHT, taken)

private const val MIN_SLACK_WEIGHT = 0.0001f

/**
 * What the overlay has to say about the changelog, which is what the layout is built around.
 *
 * Purely a UI concern - the state has no such distinction, it has a list and a loading flag.
 */
private enum class ChangelogPhase {
    /** Still on its way; the prompt stays centred and carries a hint. */
    Loading,

    /** There is something to show, and it takes the room the prompt was centring itself in. */
    Present,

    /** Nothing to show, and nothing more coming - the overlay stays the prompt it was. */
    Absent,
}

/**
 * The changelog inside the room the sheet has left for it.
 *
 * The sheet itself stays full height, but the block must not: a release with three lines to say gets
 * a block three lines tall, and only once there is more than fits does it reach the bottom edge and
 * start scrolling.
 *
 * That distinction is also what decides the padding below the last entry. The buttons float above
 * the block on a blurred scrim, so a block that reaches them has to be scrollable past them —
 * while a shorter one ends well above them and would only carry that space around as a gap.
 *
 * @param buttonsHeight height of the buttons floating above this block.
 */
@Composable
private fun ChangelogSlot(
    versions: List<Version>,
    onIssueClick: (issue: Int) -> Unit,
    buttonsHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    var availableHeight by remember { mutableStateOf(0.dp) }
    var changelogHeight by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier.onSizeChanged { size ->
            availableHeight = with(density) { size.height.toDp() }
        }
    ) {
        ChangelogList(
            versions = versions,
            onIssueClick = onIssueClick,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    changelogHeight = with(density) { size.height.toDp() }
                },
            // Only a block that has run out of room reaches under the buttons. Once the padding is
            // in, the block stays at that height, so this settles rather than flip-flopping.
            bottomPadding = if (changelogHeight >= availableHeight) buttonsHeight else 0.dp,
        )
    }
}

@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            changelog = Changelog(versions = previewChangelogVersions),
            areChangelogsLoading = false,
        )
    )
}

/** Mid-download, where the progress bar has taken the place of the buttons. */
@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayDownloadingPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            changelog = Changelog(versions = previewChangelogVersions),
            areChangelogsLoading = false,
            download = UpdateDownload.Running(
                downloadedBytes = 12_400_000,
                totalBytes = 47_000_000,
            ),
        )
    )
}

/** A download whose total size the server never said, so there is nothing to measure against. */
@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayDownloadingWithoutProgressPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            changelog = Changelog(versions = previewChangelogVersions),
            areChangelogsLoading = false,
            download = UpdateDownload.Running(
                downloadedBytes = 12_400_000,
                totalBytes = null,
            ),
        )
    )
}

/** A single short release, which the sheet is expected to wrap rather than stretch to fill. */
@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayShortChangelogPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            changelog = Changelog(versions = previewChangelogVersions.take(1)),
            areChangelogsLoading = false,
        )
    )
}

@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayLoadingChangelogPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            areChangelogsLoading = true,
        )
    )
}

@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun UpdateAvailableOverlayWithoutChangelogPreview() {
    UpdateAvailableOverlayContent(
        onEvent = {},
        state = UpdateAvailableState(
            isDismissed = false,
            currentVersion = "20260714_0930",
            latestVersion = "20260731_1812",
            areChangelogsLoading = false,
        )
    )
}