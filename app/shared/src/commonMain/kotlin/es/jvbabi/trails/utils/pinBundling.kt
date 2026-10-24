package es.jvbabi.trails.utils

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which pins on the map lie so far on top of each other that they are drawn as one
 * bundle.
 *
 * The question is asked in *screen* space, not in coordinates: two devices a street
 * apart cover each other at city zoom and stand clearly apart a few zoom levels in.
 * So everything here works on pixels, and the answer has to be recomputed whenever
 * the camera moves.
 *
 * Kept free of any map SDK and of Compose so both platforms can bundle the same way;
 * what a pin is drawn as is the caller's business, expressed through [PinSize].
 */

/** A pin's anchor position on screen, in pixels. */
data class PinPoint(
    val x: Double,
    val y: Double,
)

/** The screen size of a drawn pin, in pixels. */
data class PinSize(
    val width: Double,
    val height: Double,
)

/** A group of pins drawn as one, and where it is drawn. */
data class PinBundle<T>(
    /** The pins it holds, in the order they came in; a single one means no bundle. */
    val items: List<T>,
    /** The average of the members' anchors — where the bundle is anchored. */
    val position: PinPoint,
    /**
     * The largest screen distance between two members' anchors, in pixels; 0 for a
     * single pin. Says how far the bundle reaches at the camera it was worked out for.
     */
    val span: Double = 0.0,
)

/**
 * How much of a pin another one has to cover, in each dimension, for the two to be
 * bundled. Read as a distance: at 0.65 two pins are bundled once barely a third of a
 * pin is left between their anchors, both horizontally and vertically.
 */
const val DEFAULT_BUNDLE_COVERAGE = 0.65

private data class PinBox(
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    val width: Double,
    val height: Double,
)

/**
 * The rectangle a pin of [size] anchored at [position] covers on screen. Pins are
 * anchored at their bottom tip, so they overhang their coordinate by the full height
 * upwards and half their width to each side.
 */
private fun boxOf(position: PinPoint, size: PinSize) = PinBox(
    left = position.x - size.width / 2.0,
    right = position.x + size.width / 2.0,
    top = position.y - size.height,
    bottom = position.y,
    width = size.width,
    height = size.height,
)

/**
 * Whether two pins lie on top of each other: their overlap has to cover more than
 * [coverage] of the smaller one in *both* dimensions — the smaller one, because a
 * bundle is drawn wider than the single pin it may be covering.
 *
 * Both, not either. Two pins at the same height share their whole vertical range
 * however far apart they stand, so one dimension is already satisfied by pins that
 * merely touch at the edges — which is bundling long before anything is hidden.
 */
private fun coversEnough(a: PinBox, b: PinBox, coverage: Double): Boolean {
    val x = min(a.right, b.right) - max(a.left, b.left)
    val y = min(a.bottom, b.bottom) - max(a.top, b.top)

    return x > min(a.width, b.width) * coverage && y > min(a.height, b.height) * coverage
}

private fun centerOf(group: List<Int>, positions: List<PinPoint>) = PinPoint(
    x = group.sumOf { positions[it].x } / group.size,
    y = group.sumOf { positions[it].y } / group.size,
)

/**
 * Whether the anchors of [group] all fit into the box of a single [pin].
 *
 * This caps how far a bundle may reach on screen. Without it a bundle feeds on itself:
 * it is drawn wider than a pin, so it swallows a neighbour, which makes it wider still,
 * until it stands for devices that sit clearly apart and would be perfectly visible on
 * their own. Within the cap a bundle only ever holds pins that one pin would cover.
 */
private fun fitsIntoOnePin(group: List<Int>, positions: List<PinPoint>, pin: PinSize): Boolean {
    val xs = group.map { positions[it].x }
    val ys = group.map { positions[it].y }
    return xs.max() - xs.min() <= pin.width && ys.max() - ys.min() <= pin.height
}

/** The largest screen distance between two anchors of [group], in pixels. */
private fun spanOf(group: List<Int>, positions: List<PinPoint>): Double {
    var widest = 0.0
    for (a in group.indices) {
        for (b in a + 1..group.lastIndex) {
            val dx = positions[group[a]].x - positions[group[b]].x
            val dy = positions[group[a]].y - positions[group[b]].y
            widest = max(widest, sqrt(dx * dx + dy * dy))
        }
    }
    return widest
}

/**
 * Groups the pins that cover each other, in the order they came in; a pin that covers
 * none is a bundle of its own.
 *
 * [sizeOf] gives the size a bundle of that many pins is drawn at — one pin included.
 *
 * Bundles are grown one merge at a time, closest pair first. A bundle is drawn wider
 * than the pins it grew out of, so it may reach a neighbour that neither member did on
 * its own — and that neighbour should join, it would be hidden behind the bundle
 * otherwise. Two rules keep this from running away:
 * - A merge needs the two drawn boxes to cover each other in *both* dimensions by more
 *   than [coverage] of the smaller one. Pins at the same height that merely touch at
 *   the edges hide nothing, so they are not bundled either.
 * - A merge is refused when the members' anchors would no longer fit into a single pin
 *   (see [fitsIntoOnePin]), so no chain of neighbours grows into one bundle reaching
 *   across the map.
 *
 * Closest first, so which of two neighbours a pin joins doesn't depend on the order the
 * devices happen to arrive in.
 */
fun <T> bundleOverlappingPins(
    items: List<T>,
    coverage: Double = DEFAULT_BUNDLE_COVERAGE,
    sizeOf: (count: Int) -> PinSize,
    positionOf: (item: T) -> PinPoint,
): List<PinBundle<T>> {
    val positions = items.map(positionOf)
    val pin = sizeOf(1)

    val groups = items.indices.map { listOf(it) }.toMutableList()
    while (true) {
        val centers = groups.map { group -> centerOf(group, positions) }
        val boxes = groups.indices.map { index -> boxOf(centers[index], sizeOf(groups[index].size)) }

        var closest: Pair<Int, Int>? = null
        var closestDistance = Double.MAX_VALUE
        // O(n³) overall over the pins the map shows — a user's own devices, which is a
        // handful.
        for (a in groups.indices) {
            for (b in a + 1..groups.lastIndex) {
                if (!coversEnough(boxes[a], boxes[b], coverage)) continue
                if (!fitsIntoOnePin(groups[a] + groups[b], positions, pin)) continue
                val dx = centers[a].x - centers[b].x
                val dy = centers[a].y - centers[b].y
                val distance = dx * dx + dy * dy
                if (distance >= closestDistance) continue
                closestDistance = distance
                closest = a to b
            }
        }
        val (a, b) = closest ?: break

        // Members stay in input order, so the drawn order is stable across camera moves.
        groups[a] = (groups[a] + groups[b]).sorted()
        groups.removeAt(b)
    }

    return groups.map { group ->
        PinBundle(
            items = group.map { items[it] },
            position = centerOf(group, positions),
            span = spanOf(group, positions),
        )
    }
}
