import type {MovementItem} from "$lib/api/history/history_repository";
import {type MovementType, movementTypeOf} from "$lib/app/movements";
import type {TrailRange} from "$lib/state/map_trail.svelte";

/**
 * Turning a location history into the GeoJSON the trail layers draw: which
 * stretches are a recording gap, which are still raw, which band of the timeline
 * they fall in, which movement they belong to, and how all of that is cut into
 * features.
 *
 * Free of Svelte and of mapbox-gl, so it can be read and tested on its own.
 */

/**
 * Which stretch of the trail a segment belongs to, seen from the timeline:
 * - `outside` lies outside the window on show and is not drawn at all,
 * - `window` is on show while nothing is marked,
 * - `marked` is the marked range, and `unmarked` the rest of the window next to it —
 *   still there for orientation, but drawn thinner so the marked range stands out.
 */
export type TrailBand = "outside" | "window" | "unmarked" | "marked";

/**
 * What the line is drawn in. Here rather than in the map component because a legend has
 * to say the same thing the line does, and two lists of colours drift apart the moment
 * one of them is touched. A stretch of a known movement is drawn in its colour instead,
 * see `MOVEMENT_COLORS`.
 *
 * `track` mirrors the theme's `--primary` as hex — mapbox-gl cannot parse the oklch()
 * the token is written in. Stretches the optimizer has not reached yet are violet: they
 * are raw measurements, still carrying the jitter the optimized part has had removed.
 * The marked range is outlined in the amber the timeline marks it in.
 */
export function trailLineColors(dark: boolean) {
    return {
        track: dark ? "#e2e8f0" : "#0f172a",
        raw: dark ? "#a78bfa" : "#7c3aed",
        marked: "#f59e0b",
    };
}

/** What the timeline is showing, what is marked in it, and how the device moved. */
export interface TrailFocus {
    window: TrailRange | null;
    selection: TrailRange | null;
    /** Oldest first. A stretch inside one is drawn in its colour. */
    movements: MovementItem[];
}

export type TrailFeature = {
    type: "Feature";
    properties: {gap: boolean; raw: boolean; band: TrailBand; movement: MovementType | null};
    geometry: {type: "LineString"; coordinates: number[][]};
};

export type TrailData = {type: "FeatureCollection"; features: TrailFeature[]};

/**
 * Which band [time] falls into. With no window at all the whole trail is on show; a
 * marked range reaching past the window is only drawn as far as the window goes.
 */
export function bandOf(time: number, focus: TrailFocus): TrailBand {
    const {window, selection} = focus;
    if (window != null && (time < window.start.getTime() || time > window.end.getTime())) return "outside";
    if (selection == null) return "window";

    return time < selection.start.getTime() || time > selection.end.getTime() ? "unmarked" : "marked";
}

/** Whether [time] lies on a drawn stretch (see {@link bandOf}), where the puck may go. */
export function isHighlighted(time: number, focus: TrailFocus): boolean {
    return bandOf(time, focus) !== "outside";
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
 * Per-point flag, lined up like {@link bandFlags}: the movement the stretch ending in
 * point `i` belongs to, or `null` where it belongs to none — a stretch reaching from one
 * movement into the next, or a pause between them, is drawn in the track's own colour.
 *
 * Both lists are oldest first, so one pass over each is enough.
 */
export function movementFlags(times: ArrayLike<number>, movements: MovementItem[]): (MovementType | null)[] {
    const flags: (MovementType | null)[] = new Array(times.length).fill(null);
    let index = 0;

    for (let i = 1; i < times.length; i++) {
        const from = times[i - 1];
        const to = times[i];
        while (index < movements.length && movements[index].to < to) index++;

        const movement = movements[index];
        if (movement != null && movement.from <= from && to <= movement.to) flags[i] = movementTypeOf(movement);
    }
    return flags;
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
    movements: (MovementType | null)[] = [],
): TrailData {
    const features: TrailFeature[] = [];
    const kindOf = (segment: number) => ({
        gap: Boolean(gaps[segment]),
        raw: Boolean(raws[segment]),
        band: bands[segment] ?? "window",
        movement: movements[segment] ?? null,
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
        if (here.gap !== kind.gap || here.raw !== kind.raw || here.band !== kind.band || here.movement !== kind.movement) {
            emit(runStart, segment - 1, kind);
            runStart = segment - 1;
            kind = here;
        }
    }

    if (kind != null) emit(runStart, coordinates.length - 1, kind);
    return {type: "FeatureCollection", features};
}
