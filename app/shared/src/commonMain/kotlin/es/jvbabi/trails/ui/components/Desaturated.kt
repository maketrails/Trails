package es.jvbabi.trails.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * Drains the colour out of everything drawn below it, so an unreachable device reads
 * as one at a glance — on the map as in a list.
 *
 * A modifier rather than a colour filter handed to each picture: it covers whatever
 * the node draws, including the fallback icon of a device that has no picture and
 * anything painted behind it, and no component has to grow a parameter for it.
 *
 * The saturation is animated rather than switched, so a device coming back fades into
 * colour instead of blinking into it. At full saturation nothing is drawn through a
 * layer at all — the offscreen buffer only exists while it is needed.
 */
fun Modifier.desaturated(isDesaturated: Boolean): Modifier = composed {
    val saturation by animateFloatAsState(
        targetValue = if (isDesaturated) 0f else 1f,
        label = "saturation",
    )

    if (saturation >= 1f) return@composed this

    drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
        }

        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                drawContent()
                canvas.restore()
            }
        }
    }
}
