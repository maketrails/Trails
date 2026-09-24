@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.trails.page.ringing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Vibrate
import es.jvbabi.trails.ThemeWrapper
import es.jvbabi.trails.ui.theme.AppTheme
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import trails.app.shared.generated.resources.*

@Composable
fun RingingScreen(
    viewModel: RingingViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AppTheme(dynamicColor = false) {
        RingingContent(
            onEvent = viewModel::onEvent,
            state = state,
        )
    }
}

private val materialShapes = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Square,
    MaterialShapes.Slanted,
    MaterialShapes.Arch,
    MaterialShapes.Fan,
    MaterialShapes.Arrow,
    MaterialShapes.SemiCircle,
    MaterialShapes.Oval,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
    MaterialShapes.Diamond,
    MaterialShapes.ClamShell,
    MaterialShapes.Pentagon,
    MaterialShapes.Gem,
    MaterialShapes.Sunny,
    MaterialShapes.VerySunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Ghostish,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.Flower,
    MaterialShapes.Bun
)

@Composable
fun RingingContent(
    state: RingingState,
    onEvent: (RingingEvent) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        val shapes = remember { materialShapes }
        var currentShape by remember { mutableStateOf(shapes.random()) }
        val progress = remember { Animatable(0f) }

        LaunchedEffect(Unit) {
            while (true) {
                currentShape = shapes.random()
                progress.snapTo(0f)
                progress.animateTo(1f, tween(1000, easing = LinearEasing))
            }
        }

        val shape = currentShape.toShape()
        val layoutDirection = LocalLayoutDirection.current
        val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val minDim = minOf(size.width, size.height)
                    val squareSize = Size(minDim, minDim)
                    val outline = shape.createOutline(squareSize, layoutDirection, this)

                    val path: Path = when (outline) {
                        is Outline.Generic -> outline.path
                        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                    }

                    val maxDim = maxOf(size.width, size.height)
                    val targetScale = 2f * maxDim / minDim
                    val s = progress.value * targetScale + 0.05f

                    val cx = minDim / 2f
                    val cy = minDim / 2f
                    val offsetX = (size.width - minDim) / 2f
                    val offsetY = (size.height - minDim) / 2f

                    val matrix = Matrix()
                    matrix.translate(cx + offsetX, cy + offsetY)
                    matrix.scale(s, s)
                    matrix.translate(-cx, -cy)

                    val transformedPath = Path().apply {
                        addPath(path, Offset.Zero)
                        transform(matrix)
                    }

                    val p = progress.value
                    val alpha = if (p < 0.5f) p / 0.5f * 0.5f else (1f - p) / 0.5f * 0.5f

                    drawPath(
                        path = transformedPath,
                        color = surfaceVariant.copy(alpha = alpha),
                    )
                }
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(.3f))

            Icon(
                imageVector = PhIcons.Regular.Vibrate,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(Res.string.ringing_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(Res.string.ringing_searched_by, state.searchedByDeviceName),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.height(4.dp))

            val minutes = state.elapsedSeconds / 60
            val seconds = state.elapsedSeconds % 60
            Text(
                text = stringResource(
                    Res.string.ringing_elapsed,
                    HumanReadable.number(minutes),
                    seconds.toString().padStart(2, '0'),
                ),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = { onEvent(RingingEvent.Stop) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.size(width = 200.dp, height = 56.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.ringing_stop),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.weight(.2f))
        }
    }
}

@Composable
@Preview
@PreviewWrapper(wrapper = ThemeWrapper::class)
private fun RingingScreenPreview() {
    RingingContent(
        state = RingingState(
            isRinging = true,
            elapsedSeconds = 17,
            searchedByDeviceName = "My Device",
        ),
        onEvent = {},
    )
}
