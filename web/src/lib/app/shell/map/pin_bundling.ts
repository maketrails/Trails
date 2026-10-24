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
    /**
     * The largest screen distance between two members' anchors, in CSS pixels; 0 for
     * a single pin. Says how far the bundle reaches at the camera it was worked out for.
     */
    span: number;
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
 * Whether the anchors of [group] all fit into the box of a single pin.
 *
 * This caps how far a bundle may reach on screen. Without it a bundle feeds on
 * itself: it is drawn wider than a pin, so it swallows a neighbour, which makes it
 * wider still, until it stands for devices that sit clearly apart and would be
 * perfectly visible on their own. Within the cap a bundle only ever holds pins that
 * one pin would cover.
 */
function fitsIntoOnePin(group: number[], positions: PinPoint[]): boolean {
    const xs = group.map((index) => positions[index].x);
    const ys = group.map((index) => positions[index].y);
    return (
        Math.max(...xs) - Math.min(...xs) <= PIN_WIDTH &&
        Math.max(...ys) - Math.min(...ys) <= PIN_HEIGHT
    );
}

/** The largest screen distance between two anchors of [group], in CSS pixels. */
function spanOf(group: number[], positions: PinPoint[]): number {
    let widest = 0;
    for (let a = 0; a < group.length; a++) {
        for (let b = a + 1; b < group.length; b++) {
            const dx = positions[group[a]].x - positions[group[b]].x;
            const dy = positions[group[a]].y - positions[group[b]].y;
            widest = Math.max(widest, Math.hypot(dx, dy));
        }
    }
    return widest;
}

/**
 * Groups the pins that cover each other, in the order they came in; a pin that
 * covers none is a bundle of its own.
 *
 * Bundles are grown one merge at a time, closest pair first. A bundle is drawn
 * wider than the pins it grew out of, so it may reach a neighbour that neither
 * member did on its own — and that neighbour should join, it would be hidden
 * behind the pill otherwise. Two rules keep this from running away:
 * - A merge needs the two drawn boxes to cover each other in *both* dimensions
 *   (see {@link coversEnough}).
 * - A merge is refused when the members' anchors would no longer fit into a single
 *   pin (see {@link fitsIntoOnePin}), so no chain of neighbours grows into one
 *   bundle reaching across the map.
 *
 * Closest first, so which of two neighbours a pin joins doesn't depend on the order
 * the devices happen to arrive in.
 */
export function bundleOverlappingPins<T>(
    items: T[],
    positionOf: (item: T) => PinPoint,
): PinBundle<T>[] {
    const positions = items.map(positionOf);

    const groups = items.map((_, index) => [index]);
    for (;;) {
        const centers = groups.map((group) => centerOf(group, positions));
        const boxes = groups.map((group, index) => boxOf(centers[index], bundleSize(group.length)));

        let closest: [number, number] | null = null;
        let closestDistance = Infinity;
        // O(n³) overall over the pins the map shows — a user's own devices plus the
        // shares they were given, which is a handful, not a feed.
        for (let a = 0; a < groups.length; a++) {
            for (let b = a + 1; b < groups.length; b++) {
                if (!coversEnough(boxes[a], boxes[b])) continue;
                if (!fitsIntoOnePin([...groups[a], ...groups[b]], positions)) continue;
                const distance = (centers[a].x - centers[b].x) ** 2 + (centers[a].y - centers[b].y) ** 2;
                if (distance >= closestDistance) continue;
                closestDistance = distance;
                closest = [a, b];
            }
        }
        if (closest == null) break;

        // Members stay in input order, so the drawn order is stable across camera moves.
        const [a, b] = closest;
        groups[a] = [...groups[a], ...groups[b]].sort((left, right) => left - right);
        groups.splice(b, 1);
    }

    return groups.map((group) => ({
        items: group.map((index) => items[index]),
        position: centerOf(group, positions),
        span: spanOf(group, positions),
    }));
}
