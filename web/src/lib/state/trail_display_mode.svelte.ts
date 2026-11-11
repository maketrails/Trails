/**
 * How a detail view's history is drawn on the map:
 * - `none` draws nothing but the device's pin — the timeline still works, and the
 *   puck still answers it, there is just no line or heat laid over the map,
 * - `trail` draws the history as a line (the default),
 * - `heatmap` draws where the device spent its time, as heat.
 *
 * Which of the two series is drawn (raw or optimized) is a separate choice, made per
 * view; every mode works with both.
 */
export type TrailDisplayMode = "none" | "trail" | "heatmap";

/** Every mode, in the order the switcher offers them. */
export const TRAIL_DISPLAY_MODES: readonly TrailDisplayMode[] = ["none", "trail", "heatmap"];

export const DEFAULT_TRAIL_DISPLAY_MODE: TrailDisplayMode = "trail";

/** Where the choice is remembered. Per viewer and per browser, deliberately nothing more. */
export const TRAIL_DISPLAY_MODE_STORAGE_KEY = "trails.map.trail_display_mode";

/** [value] as a mode, or the default when it is none — a stale or tampered entry included. */
export function parseTrailDisplayMode(value: unknown): TrailDisplayMode {
    return TRAIL_DISPLAY_MODES.includes(value as TrailDisplayMode)
        ? (value as TrailDisplayMode)
        : DEFAULT_TRAIL_DISPLAY_MODE;
}

/**
 * The remembered mode. Storage can be missing (while rendering on the server), empty
 * or throw on access (a private window, blocked site data), and each of those simply
 * means the default.
 */
export function readStoredTrailDisplayMode(): TrailDisplayMode {
    try {
        return parseTrailDisplayMode(globalThis.localStorage?.getItem(TRAIL_DISPLAY_MODE_STORAGE_KEY));
    } catch {
        return DEFAULT_TRAIL_DISPLAY_MODE;
    }
}

function storeTrailDisplayMode(next: TrailDisplayMode) {
    try {
        globalThis.localStorage?.setItem(TRAIL_DISPLAY_MODE_STORAGE_KEY, next);
    } catch {
        // Not remembered then; the choice still holds for as long as the page is open.
    }
}

// A preference of the viewer rather than of a view, so it is not claimed like the
// trail is: opening another device keeps the mode, and so does switching its source.
let mode = $state<TrailDisplayMode>(readStoredTrailDisplayMode());

/** Switches how the history is drawn, and remembers it for the next visit. */
export function setTrailDisplayMode(next: TrailDisplayMode) {
    mode = next;
    storeTrailDisplayMode(next);
}

/** The current display mode (reactive). */
export const trailDisplayMode = {
    get mode() {
        return mode;
    },
};
