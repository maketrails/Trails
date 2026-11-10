import type {HistoryPoint} from "$lib/api/history/history_repository";
import type {TrailRange} from "$lib/state/map_trail.svelte";

/**
 * Turning a location history into the GeoJSON the heatmap layer draws.
 *
 * A heatmap answers "where did the device spend its time", so every point is weighed
 * by how long the device stayed there — the time until the next point — rather than
 * merely counted. That keeps the two series comparable: the raw one records at a
 * steady rate, so counting would work there, but the optimized one collapses a long
 * stay into a handful of points, and counting would make the place it spent all night
 * look like a place it drove past.
 *
 * Points are summed into a fine grid before they reach the map. A history runs to
 * hundreds of thousands of points, most of them piled on the same few places, and the
 * grid hands mapbox one feature per place instead of one per point.
 *
 * Free of Svelte and of mapbox-gl, so it can be read and tested on its own.
 */

/** Edge of a grid cell in degrees, about eleven metres — finer than the heat blurs at any zoom. */
export const HEATMAP_CELL_DEGREES = 1e-4;

/** Most cells handed to the map. Past it, the grid is coarsened until it fits. */
export const HEATMAP_MAX_CELLS = 30_000;

/**
 * Longest a single point may count for. Beyond it the device was not recording, and
 * where it was in between is unknown — giving the last point before a gap all of that
 * time would turn every place a phone ran flat into the hottest spot on the map.
 */
export const HEATMAP_MAX_DWELL_MS = 10 * 60_000;

/** Shortest a point counts for, so a stretch driven past quickly still leaves a trace. */
export const HEATMAP_MIN_DWELL_MS = 5_000;

/** What the timeline is showing, and what is marked in it. */
export interface HeatmapFocus {
    window: TrailRange | null;
    selection: TrailRange | null;
}

export type HeatmapFeature = {
    type: "Feature";
    /** `weight` is 0–1, relative to the heaviest cell. */
    properties: {weight: number};
    geometry: {type: "Point"; coordinates: [number, number]};
};

export type HeatmapData = {type: "FeatureCollection"; features: HeatmapFeature[]};

export const EMPTY_HEATMAP: HeatmapData = {type: "FeatureCollection", features: []};

/**
 * The stretch of time the heat is made of, as epoch milliseconds, or `null` for all of it.
 *
 * Unlike the line, which draws the rest of the window thinner next to a marked range,
 * the heat leaves the rest out altogether: heat adds up, so a "dimmed" point would
 * still warm everything around it, and the marked range would drown in the window it
 * was marked in. A marked range reaching past the window counts only as far as the
 * window goes, like on the line.
 */
export function heatmapRange(focus: HeatmapFocus): {start: number; end: number} | null {
    const {window, selection} = focus;
    if (window == null && selection == null) return null;

    const start = Math.max(window?.start.getTime() ?? -Infinity, selection?.start.getTime() ?? -Infinity);
    const end = Math.min(window?.end.getTime() ?? Infinity, selection?.end.getTime() ?? Infinity);
    return {start, end};
}

/** The first index in [points] (oldest first) recorded at or after [time]. */
export function firstIndexFrom(points: HistoryPoint[], time: number): number {
    let low = 0;
    let high = points.length;
    while (low < high) {
        const middle = (low + high) >> 1;
        if (points[middle].timestamp < time) low = middle + 1;
        else high = middle;
    }
    return low;
}

/** Weighted sums of the points that fell into one cell, so the cell sits where they did. */
interface Cell {
    lng: number;
    lat: number;
    weight: number;
}

/** Sums [cells] into a grid of [size] degrees, each new cell at the weighted centre of its parts. */
function bin(cells: Iterable<Cell>, size: number): Map<number, Cell> {
    const grid = new Map<number, Cell>();
    // Rows a few million apart: at the finest size a row holds 3.6 million cells, and
    // the key stays far inside what a double counts exactly.
    const rowStride = Math.ceil(360 / size) + 1;

    for (const cell of cells) {
        const lng = cell.lng / cell.weight;
        const lat = cell.lat / cell.weight;
        const key = Math.floor((lat + 90) / size) * rowStride + Math.floor((lng + 180) / size);

        const existing = grid.get(key);
        if (existing == null) grid.set(key, {lng: cell.lng, lat: cell.lat, weight: cell.weight});
        else {
            existing.lng += cell.lng;
            existing.lat += cell.lat;
            existing.weight += cell.weight;
        }
    }
    return grid;
}

/**
 * The heat for [points] (oldest first) as the timeline currently frames it, see
 * {@link heatmapRange}.
 *
 * Cell weights are scaled by their square root before being made relative to the
 * heaviest one: a stay at home outweighs a commute a hundred times over, and on a
 * linear scale the commute would vanish next to it.
 */
export function heatmapData(
    points: HistoryPoint[],
    focus: HeatmapFocus,
    maxCells: number = HEATMAP_MAX_CELLS,
): HeatmapData {
    const range = heatmapRange(focus);
    const from = range == null ? 0 : firstIndexFrom(points, range.start);
    const to = range == null ? points.length : firstIndexFrom(points, range.end + 1);
    if (to <= from) return EMPTY_HEATMAP;

    const end = range?.end ?? Infinity;
    function* weighted(): Generator<Cell> {
        for (let i = from; i < to; i++) {
            const point = points[i];
            // How long the device stayed here: until the next point, but not past the
            // end of the range — what comes after it is not being looked at.
            const next = i + 1 < points.length ? Math.min(points[i + 1].timestamp, end) : point.timestamp;
            const dwell = Math.min(HEATMAP_MAX_DWELL_MS, Math.max(HEATMAP_MIN_DWELL_MS, next - point.timestamp));
            yield {lng: point.longitude * dwell, lat: point.latitude * dwell, weight: dwell};
        }
    }

    let size = HEATMAP_CELL_DEGREES;
    let grid = bin(weighted(), size);
    while (grid.size > maxCells) {
        size *= 2;
        grid = bin(grid.values(), size);
    }

    let heaviest = 0;
    for (const cell of grid.values()) heaviest = Math.max(heaviest, cell.weight);
    const scale = Math.sqrt(heaviest);

    const features: HeatmapFeature[] = [];
    for (const cell of grid.values()) {
        features.push({
            type: "Feature",
            properties: {weight: Math.sqrt(cell.weight) / scale},
            geometry: {type: "Point", coordinates: [cell.lng / cell.weight, cell.lat / cell.weight]},
        });
    }
    return {type: "FeatureCollection", features};
}

/**
 * What the heat is drawn in, from barely there to the most time spent, as stops for
 * `heatmap-color` (density 0–1). Here rather than in the map component for the same
 * reason the line's colours are: the legend has to say what the map does.
 *
 * Cool to warm on both styles, but deeper on the light map, where pale yellow would
 * vanish into the paper, and brighter on the dark one, where it reads best. Density 0
 * is always transparent, so the map shows through wherever the device never was.
 */
export function heatmapColors(dark: boolean): [number, string][] {
    return dark
        ? [
            [0, "rgba(59, 130, 246, 0)"],
            [0.15, "rgba(59, 130, 246, 0.55)"],
            [0.35, "#8b5cf6"],
            [0.55, "#ec4899"],
            [0.8, "#f59e0b"],
            [1, "#fde68a"],
        ]
        : [
            [0, "rgba(37, 99, 235, 0)"],
            [0.15, "rgba(37, 99, 235, 0.5)"],
            [0.35, "#7c3aed"],
            [0.55, "#db2777"],
            [0.8, "#ea580c"],
            [1, "#b91c1c"],
        ];
}
