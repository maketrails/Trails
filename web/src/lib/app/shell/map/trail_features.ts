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
 *
 * A stretch marked in [breaks] is not drawn at all: what lay between those two
 * points was clipped away for being off screen (see trail_display), and a line
 * across it would claim a journey that never happened. The run ends there and the
 * next one starts on the far side.
 */
export function trailData(
    coordinates: number[][],
    gaps: ArrayLike<number | boolean>,
    raws: ArrayLike<number | boolean> = [],
    bands: TrailBand[] = [],
    breaks: ArrayLike<number | boolean> = [],
): TrailData {
    const features: TrailFeature[] = [];
    const kindOf = (segment: number) => ({
        gap: Boolean(gaps[segment]),
        raw: Boolean(raws[segment]),
        band: bands[segment] ?? "window",
    });

    // A LineString needs two positions, so a run of one point is nothing to draw.
    const emit = (from: number, to: number, kind: ReturnType<typeof kindOf>) => {
        if (to - from < 1) return;
        features.push({
            type: "Feature",
            properties: kind,
            geometry: {type: "LineString", coordinates: coordinates.slice(from, to + 1)},
        });
    };

    let runStart = 0;
    let kind = coordinates.length > 1 ? kindOf(1) : null;

    for (let segment = 1; segment < coordinates.length; segment++) {
        if (Boolean(breaks[segment])) {
            if (kind != null) emit(runStart, segment - 1, kind);
            runStart = segment;
            kind = segment + 1 < coordinates.length ? kindOf(segment + 1) : null;
            continue;
        }

        const here = kindOf(segment);
        if (kind == null) kind = here;
        if (here.gap !== kind.gap || here.raw !== kind.raw || here.band !== kind.band) {
            emit(runStart, segment - 1, kind);
            runStart = segment - 1;
            kind = here;
        }
    }

    if (kind != null) emit(runStart, coordinates.length - 1, kind);
    return {type: "FeatureCollection", features};
}
