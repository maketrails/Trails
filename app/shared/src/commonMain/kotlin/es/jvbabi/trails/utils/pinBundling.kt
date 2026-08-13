package es.jvbabi.trails.utils

import kotlin.math.max
import kotlin.math.min

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
 * One merging pass over [groups] (each a list of indices into [positions]), or `null`
 * when nothing overlapped any more.
 *
 * Overlap is treated as transitive: A over B and B over C makes one group of three,
 * even where A and C don't touch — otherwise a pin could belong to two bundles at
 * once and there would be no saying which one draws it.
 */
private fun mergeOverlapping(
    groups: List<List<Int>>,
    positions: List<PinPoint>,
    coverage: Double,
    sizeOf: (count: Int) -> PinSize,
): List<List<Int>>? {
    val boxes = groups.map { group -> boxOf(centerOf(group, positions), sizeOf(group.size)) }

    // Union-find: `parent[i]` points at another group of the same bundle, the root
    // standing for the bundle itself.
    val parent = IntArray(groups.size) { it }
    fun rootOf(index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    var merged = false
    // O(n²) over the pins the map shows — a user's own devices, which is a handful.
    for (a in boxes.indices) {
        for (b in a + 1..boxes.lastIndex) {
            if (!coversEnough(boxes[a], boxes[b], coverage)) continue
            val rootA = rootOf(a)
            val rootB = rootOf(b)
            if (rootA == rootB) continue
            parent[rootB] = rootA
            merged = true
        }
    }
    if (!merged) return null

    // Rebuilt in input order, so the drawn order stays stable across passes.
    val bundles = LinkedHashMap<Int, MutableList<Int>>()
    for (index in groups.indices) {
        bundles.getOrPut(rootOf(index)) { mutableListOf() }.addAll(groups[index])
    }
    return bundles.values.toList()
}

/**
 * Groups the pins that cover each other, in the order they came in; a pin that covers
 * none is a bundle of its own.
 *
 * [sizeOf] gives the size a bundle of that many pins is drawn at — one pin included.
 * Merging is repeated until nothing changes: a bundle is drawn wider than the single
 * pin it grew out of, so it can reach a neighbour that neither of its members did on
 * their own.
 */
fun <T> bundleOverlappingPins(
    items: List<T>,
    coverage: Double = DEFAULT_BUNDLE_COVERAGE,
    sizeOf: (count: Int) -> PinSize,
    positionOf: (item: T) -> PinPoint,
): List<PinBundle<T>> {
    val positions = items.map(positionOf)

    var groups = items.indices.map { listOf(it) }
    while (true) {
        groups = mergeOverlapping(groups, positions, coverage, sizeOf) ?: break
    }

    return groups.map { group ->
        PinBundle(
            items = group.map { items[it] },
            position = centerOf(group, positions),
        )
    }
}
