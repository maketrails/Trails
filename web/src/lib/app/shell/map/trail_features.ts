import type {HistoryPoint} from "$lib/api/history/history_repository";
import type {TrailRange} from "$lib/state/map_trail.svelte";

/**
 * Turning a location history into the GeoJSON the trail layers draw: which
 * stretches are a recording gap, which are still raw, which band of the timeline
 * they fall in, and how all of that is cut into features.
 *
 * Free of Svelte and of mapbox-gl, so it can be read and tested on its own.
 */

/**
 * Anything longer than this between two consecutive points is a recording gap:
 * where the device actually went in between is unknown, so that stretch is drawn
 * as a faint dotted hint instead of a solid line.
 */
export const TRAIL_GAP_MS = 60_000;

/** Which stretch of the trail a segment belongs to, seen from the timeline. */
export type TrailBand = "before" | "window" | "after" | "selected";

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

export function toCoordinates(points: HistoryPoint[]): number[][] {
    return points.map((point) => [point.longitude, point.latitude]);
}

/**
 * Per-point flag: `gaps[i]` marks the segment from point `i - 1` to `i` as a gap.
 * Index 0 has no incoming segment and is always false, which keeps the flags
 * aligned with {@link toCoordinates} — the grow-in animation relies on that.
 */
export function gapFlags(points: HistoryPoint[]): boolean[] {
    return points.map((point, i) => i > 0 && point.timestamp - points[i - 1].timestamp > TRAIL_GAP_MS);
}

/**
 * Per-point flag in the same "incoming segment" convention as {@link gapFlags}:
 * `raws[i]` marks the segment from point `i - 1` to `i` as unoptimized. The
 * changeover segment counts as unoptimized — it is the one connection no optimizer
 * has looked at.
 */
export function rawFlags(points: HistoryPoint[]): boolean[] {
    return points.map((point, i) => i > 0 && point.is_raw);
}

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
 * Per-point flag in the same convention again: the segment ending in point `i` is
 * coloured for the band that point falls in.
 */
export function bandFlags(points: HistoryPoint[], focus: TrailFocus): TrailBand[] {
    return points.map((point) => bandOf(point.timestamp, focus));
}

/**
 * Splits the coordinates into one LineString per run of same-kind segments, so the
 * solid, the dotted and the casing layer can each filter for their own features.
 * Runs share their boundary point, which keeps the line visually continuous.
 */
export function trailData(
    coordinates: number[][],
    gaps: boolean[],
    raws: boolean[] = [],
    bands: TrailBand[] = [],
): TrailData {
    const features: TrailFeature[] = [];
    // A LineString needs at least two positions; fewer means nothing to draw.
    let runStart = 1;
    for (let segment = 1; segment < coordinates.length; segment++) {
        const gap = gaps[segment] ?? false;
        const raw = raws[segment] ?? false;
        const band = bands[segment] ?? "window";
        const isLast = segment === coordinates.length - 1;
        const sameKind =
            (gaps[segment + 1] ?? false) === gap
            && (raws[segment + 1] ?? false) === raw
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
