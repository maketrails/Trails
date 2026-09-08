/**
 * The window a timeline shows, and the only thing that moves it: every gesture,
 * button and keystroke ends up in one of the methods here, and every one of them
 * lands inside the bounds.
 *
 * The window itself is not held here. It is read and written through the accessors
 * the caller passes in, so it can live wherever it belongs — as a component's
 * bindable prop, say — and stay the single source of truth.
 */

/** The stretch of time a timeline shows. */
export interface TimelineView {
    start: Date;
    end: Date;
}

export interface TimelineWindowOptions {
    /** Everything there is data for. The window can neither leave it nor exceed it. */
    bounds: () => {oldest: Date; newest: Date};
    /** The window as it stands, or `null` before it has one. */
    view: () => TimelineView | null;
    /** Where a new window goes. */
    publish: (view: TimelineView) => void;
    /** Tightest window the caller may zoom to, unless the bounds are shorter still. */
    closestSpan?: number;
}

export interface TimelineWindow {
    /** Start, length and end of the window, in epoch milliseconds. */
    readonly start: number;
    readonly span: number;
    readonly end: number;
    /** The bounds, in epoch milliseconds. */
    readonly oldest: number;
    readonly newest: number;
    /** How short and how long the window may get. */
    readonly minSpan: number;
    readonly maxSpan: number;
    /** Publishes a window, clamped to the zoom limits and to the bounds. */
    set(start: number, span: number): void;
    /**
     * Zooms by [factor] — below 1 zooms in — around [anchor], a 0…1 position across
     * the width. The moment under the anchor stays where it is.
     */
    zoomBy(factor: number, anchor?: number): void;
    /** Moves the window by [deltaMs]; positive scrolls towards newer moments. */
    panBy(deltaMs: number): void;
    /** Shows exactly [start]…[end], as far as the limits allow. */
    show(start: Date, end: Date): void;
    /** Frames everything — the widest the window can get. */
    fit(): void;
    /** Frames the newest [span] milliseconds, or everything if that is shorter. */
    openOn(span: number): void;
}

/** Ten seconds across the whole width, unless the bounds are shorter than that. */
const CLOSEST_SPAN = 10_000;

const clamp = (value: number, low: number, high: number) => Math.min(Math.max(value, low), high);

export function createTimelineWindow(options: TimelineWindowOptions): TimelineWindow {
    const closest = options.closestSpan ?? CLOSEST_SPAN;

    const oldest = () => options.bounds().oldest.getTime();
    const newest = () => options.bounds().newest.getTime();

    // Falling back to the bounds keeps every reader valid in the moment between
    // being created and being given a window.
    const start = () => options.view()?.start.getTime() ?? oldest();
    const span = () => Math.max(1, (options.view()?.end.getTime() ?? newest()) - start());

    const maxSpan = () => Math.max(newest() - oldest(), 1);
    const minSpan = () => Math.min(closest, maxSpan());

    const window: TimelineWindow = {
        get start() {
            return start();
        },
        get span() {
            return span();
        },
        get end() {
            return start() + span();
        },
        get oldest() {
            return oldest();
        },
        get newest() {
            return newest();
        },
        get minSpan() {
            return minSpan();
        },
        get maxSpan() {
            return maxSpan();
        },
        set(nextStart, nextSpan) {
            const length = clamp(nextSpan, minSpan(), maxSpan());
            const from = clamp(nextStart, oldest(), newest() - length);
            options.publish({start: new Date(from), end: new Date(from + length)});
        },
        zoomBy(factor, anchor = 0.5) {
            const length = clamp(span() * factor, minSpan(), maxSpan());
            window.set(start() + (span() - length) * anchor, length);
        },
        panBy(deltaMs) {
            window.set(start() + deltaMs, span());
        },
        show(from, to) {
            window.set(from.getTime(), to.getTime() - from.getTime());
        },
        fit() {
            window.set(oldest(), maxSpan());
        },
        openOn(length) {
            const opening = Math.min(length, maxSpan());
            window.set(newest() - opening, opening);
        },
    };

    return window;
}
