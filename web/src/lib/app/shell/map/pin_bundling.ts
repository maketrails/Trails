/**
 * Which pins on the map lie so far on top of each other that they are drawn as
 * one bundle.
 *
 * The question is asked in *screen* space, not in coordinates: two devices a
 * street apart cover each other at city zoom and stand clearly apart a few zoom
 * levels in. So everything here works on pixels, and the answer has to be
 * recomputed whenever the camera moves.
 */

/** A pin's anchor position on screen, in CSS pixels. */
export interface PinPoint {
    x: number;
    y: number;
}

/** The screen size of a drawn pin, in CSS pixels. */
export interface PinSize {
    width: number;
    height: number;
}

/** A group of pins drawn as one, and where it is drawn. */
export interface PinBundle<T> {
    /** The pins it holds, in the order they came in; a single one means no bundle. */
    items: T[];
    /** The average of the members' anchors — where the bundle is anchored. */
    position: PinPoint;
}

/**
 * MapPin's rendered SVG size. The marker is anchored at its bottom tip, so a pin
 * overhangs its coordinate by the full height upwards and half its width to each
 * side (and nothing below).
 */
export const PIN_WIDTH = 60;
export const PIN_HEIGHT = 67;

/** Geometry of the bundle pill (MapBundle), which draws its members side by side. */
export const BUNDLE_AVATAR_SIZE = 44;
export const BUNDLE_GAP = 4;
export const BUNDLE_PADDING = 6;
export const BUNDLE_BORDER = 1;
/** Height of the pointer below the pill; like a pin, a bundle is anchored at its tip. */
export const BUNDLE_TAIL_HEIGHT = 8;
/** Members per row. Beyond that the pill wraps, so it grows in height, not endlessly wide. */
export const BUNDLE_COLUMNS = 4;

/**
 * How much of a pin another one has to cover, in each dimension, for the two to be
 * bundled. Read as a distance: at 0.65 two pins are bundled once barely a third of
 * a pin is left between their anchors, both horizontally and vertically — pins that
 * clearly overlap keep standing on their own for a while yet.
 */
const BUNDLE_COVERAGE = 0.65;

/** The screen size a bundle of [count] pins is drawn at. */
export function bundleSize(count: number): PinSize {
    if (count <= 1) return {width: PIN_WIDTH, height: PIN_HEIGHT};

    // The pill is box-sized, so its border eats into the room the members need —
    // hence it is part of the frame here rather than an afterthought.
    const frame = 2 * (BUNDLE_PADDING + BUNDLE_BORDER);
    const columns = Math.min(count, BUNDLE_COLUMNS);
    const rows = Math.ceil(count / BUNDLE_COLUMNS);
    return {
        width: frame + columns * BUNDLE_AVATAR_SIZE + (columns - 1) * BUNDLE_GAP,
        height: frame + rows * BUNDLE_AVATAR_SIZE + (rows - 1) * BUNDLE_GAP + BUNDLE_TAIL_HEIGHT,
    };
}

interface PinBox {
    left: number;
    right: number;
    top: number;
    bottom: number;
    width: number;
    height: number;
}

/** The rectangle a pin of [size] anchored at [position] covers on screen. */
function boxOf(position: PinPoint, size: PinSize): PinBox {
    return {
        left: position.x - size.width / 2,
        right: position.x + size.width / 2,
        top: position.y - size.height,
        bottom: position.y,
        width: size.width,
        height: size.height,
    };
}

/**
 * Whether two pins lie on top of each other: their overlap has to cover more than
 * {@link BUNDLE_COVERAGE} of the smaller one in *both* dimensions — the smaller
 * one, because a bundle pill is wider than the single pin it may be covering.
 *
 * Both, not either. Two pins at the same height share their whole vertical range
 * however far apart they stand, so one dimension is already satisfied by pins that
 * merely touch at the edges — which is bundling long before anything is hidden.
 */
function coversEnough(a: PinBox, b: PinBox): boolean {
    const x = Math.min(a.right, b.right) - Math.max(a.left, b.left);
    const y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);

    return (
        x > Math.min(a.width, b.width) * BUNDLE_COVERAGE &&
        y > Math.min(a.height, b.height) * BUNDLE_COVERAGE
    );
}

/**
 * One merging pass over [groups] (each a list of indices into [positions]), or
 * `null` when nothing overlapped any more.
 *
 * Overlap is treated as transitive: A over B and B over C makes one group of
 * three, even where A and C don't touch — otherwise a pin could belong to two
 * bundles at once and there would be no saying which one draws it.
 */
function mergeOverlapping(groups: number[][], positions: PinPoint[]): number[][] | null {
    const boxes = groups.map((group) => boxOf(centerOf(group, positions), bundleSize(group.length)));

    // Union-find: `parent[i]` points at another group of the same bundle, the root
    // standing for the bundle itself.
    const parent = groups.map((_, index) => index);
    const rootOf = (index: number): number => {
        while (parent[index] !== index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    };

    let merged = false;
    // O(n²) over the pins the map shows — a user's own devices plus the shares they
    // were given, which is a handful, not a feed.
    for (let a = 0; a < boxes.length; a++) {
        for (let b = a + 1; b < boxes.length; b++) {
            if (!coversEnough(boxes[a], boxes[b])) continue;
            const rootA = rootOf(a);
            const rootB = rootOf(b);
            if (rootA === rootB) continue;
            parent[rootB] = rootA;
            merged = true;
        }
    }
    if (!merged) return null;

    // Rebuilt in input order, so the drawn order stays stable across passes.
    const bundles = new Map<number, number[]>();
    for (let index = 0; index < groups.length; index++) {
        const root = rootOf(index);
        const bundle = bundles.get(root);
        if (bundle == null) bundles.set(root, [...groups[index]]);
        else bundle.push(...groups[index]);
    }
    return [...bundles.values()];
}

function centerOf(group: number[], positions: PinPoint[]): PinPoint {
    let x = 0;
    let y = 0;
    for (const index of group) {
        x += positions[index].x;
        y += positions[index].y;
    }
    return {x: x / group.length, y: y / group.length};
}

/**
 * Groups the pins that cover each other, in the order they came in; a pin that
 * covers none is a bundle of its own.
 *
 * Merging is repeated until nothing changes: a bundle is drawn wider than the
 * single pin it grew out of, so it can reach a neighbour that neither of its
 * members did on their own.
 */
export function bundleOverlappingPins<T>(
    items: T[],
    positionOf: (item: T) => PinPoint,
): PinBundle<T>[] {
    const positions = items.map(positionOf);

    let groups = items.map((_, index) => [index]);
    for (;;) {
        const merged = mergeOverlapping(groups, positions);
        if (merged == null) break;
        groups = merged;
    }

    return groups.map((group) => ({
        items: group.map((index) => items[index]),
        position: centerOf(group, positions),
    }));
}
