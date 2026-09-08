import type {Axis} from "./timeline_scale";
import type {TimelineRange} from "./timeline_window";

/**
 * Marking a range on a timeline: where its ends may land, and how they behave
 * while being dragged.
 *
 * Free of Svelte and of the DOM, like the rest of the model.
 */

/** How close to a candidate an end has to be dragged to land on it, in pixels. */
export const SNAP_DISTANCE = 12;

/**
 * The moments an end snaps to: every mark the axis is currently drawing, plus the
 * ends of the bounds.
 *
 * Deliberately nothing else. What a reader can see is what they can aim at — a
 * grid line, a labelled tick, the start of a day — and snapping to something
 * invisible would read as the end refusing to sit where it was put.
 */
export function snapTargets(axis: Axis, bounds: {oldest: number; newest: number}): number[] {
    return [
        bounds.oldest,
        bounds.newest,
        ...axis.separators.map((separator) => separator.time),
        ...axis.ticks.map((tick) => tick.time),
        ...axis.minorTicks,
    ];
}

/**
 * The candidate [time] should land on, or [time] itself while none is near enough.
 * [msPerPixel] turns {@link SNAP_DISTANCE} into a distance in time, so the pull
 * stays the same on screen at every zoom level.
 */
export function snapToTargets(time: number, targets: number[], msPerPixel: number, distance = SNAP_DISTANCE): number {
    const reach = distance * msPerPixel;

    let best = time;
    let bestGap = reach;
    for (const target of targets) {
        const gap = Math.abs(target - time);
        if (gap <= bestGap) {
            best = target;
            bestGap = gap;
        }
    }
    return best;
}

/** A range from two moments in either order, so a backwards sweep still marks something. */
export function rangeOf(from: number, to: number): TimelineRange {
    return {start: new Date(Math.min(from, to)), end: new Date(Math.max(from, to))};
}

/** Whether [range] covers enough time to be a selection rather than a stray click. */
export function isMeaningful(range: TimelineRange, msPerPixel: number, pixels = 3): boolean {
    return range.end.getTime() - range.start.getTime() > pixels * msPerPixel;
}
