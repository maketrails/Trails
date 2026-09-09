import type {TrailRange} from "$lib/state/map_trail.svelte";

/**
 * Turning a location history into the GeoJSON the trail layers draw: which
 * stretches are a recording gap, which are still raw, which band of the timeline
 * they fall in, and how all of that is cut into features.
 *
 * Free of Svelte and of mapbox-gl, so it can be read and tested on its own.
 */

/** Which stretch of the trail a segment belongs to, seen from the timeline. */
export type TrailBand = "before" | "window" | "after" | "selected";

/**
 * What each band is drawn in. Here rather than in the map component because a
 * legend has to say the same thing the line does, and two lists of colours drift
 * apart the moment one of them is touched.
 *
 * These are read against the *basemap*, which is light in one theme and near-black
 * in the other — so the grey of a stretch that has stepped back has to turn with it,
 * or it disappears into the ground it is drawn on. White and amber carry themselves
 * on both, and the outline under them (see the map's casing) does the rest.
 */
export function trailBandColors(dark: boolean): Record<TrailBand, string> {
    return {
        window: "#ffffff",
        before: dark ? "rgba(148,163,184,0.6)" : "rgba(51,65,85,0.55)",
        after: "rgba(255,255,255,0.8)",
        selected: "#f59e0b"
    };
}

/** What the timeline is showing and what is marked in it. */
export interface TrailFocus {
    window: TrailRange | null;
    selection: TrailRange | null;
}

export type TrailFeature = {
    type: "Feature";
    properties: {gap: boolean; raw: boolean; band: TrailBand};
    geometry: {type: "LineString"; coordinates: number[][]};
};

export type TrailData = {type: "FeatureCollection"; features: TrailFeature[]};

/**
 * Which band [time] falls into.
 *
 * There is one highlighted stretch at a time: the marked range if there is one,
 * the window otherwise. Everything else reads as before or after it — a marked
 * range takes the emphasis off the rest of the window too, because two highlights
 * at once say nothing about which of them was asked for.
 *
 * With neither a window nor a range the whole trail is on show.
 */
export function bandOf(time: number, focus: TrailFocus): TrailBand {
    const marked = focus.selection != null;
    const range = focus.selection ?? focus.window;
    if (range == null) return "window";

    if (time < range.start.getTime()) return "before";
    if (time > range.end.getTime()) return "after";
    return marked ? "selected" : "window";
}

/**
 * Per-point flag: the stretch ending in point `i` is coloured for the band that
 * point falls in. Index 0 has no incoming stretch, but carries a band anyway so the
 * array lines up with the coordinates.
 *
 * This is the one thing recomputed on every move of the timeline, so it runs over
 * the drawn track's times (see trail_display) rather than over the recorded points —
 * a few thousand numbers in a typed array instead of a million objects.
 */
export function bandFlags(times: ArrayLike<number>, focus: TrailFocus): TrailBand[] {
    const bands: TrailBand[] = new Array(times.length);
    for (let i = 0; i < times.length; i++) bands[i] = bandOf(times[i], focus);
    return bands;
}

/**
 * Splits the coordinates into one LineString per run of same-kind segments, so the
 * solid, the dotted and the casing layer can each filter for their own features.
 * Runs share their boundary point, which keeps the line visually continuous.
 */
export function trailData(
    coordinates: number[][],
    gaps: ArrayLike<number | boolean>,
    raws: ArrayLike<number | boolean> = [],
    bands: TrailBand[] = [],
): TrailData {
    const features: TrailFeature[] = [];
    // A LineString needs at least two positions; fewer means nothing to draw.
    let runStart = 1;
    for (let segment = 1; segment < coordinates.length; segment++) {
        const gap = Boolean(gaps[segment]);
        const raw = Boolean(raws[segment]);
        const band = bands[segment] ?? "window";
        const isLast = segment === coordinates.length - 1;
        const sameKind =
            Boolean(gaps[segment + 1]) === gap
            && Boolean(raws[segment + 1]) === raw
            && (bands[segment + 1] ?? "window") === band;
        if (!isLast && sameKind) continue;

        features.push({
            type: "Feature",
            properties: {gap, raw, band},
            geometry: {type: "LineString", coordinates: coordinates.slice(runStart - 1, segment + 1)},
        });
        runStart = segment + 1;
    }
    return {type: "FeatureCollection", features};
}
