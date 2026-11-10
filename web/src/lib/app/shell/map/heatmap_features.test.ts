import {describe, expect, it} from "vitest";
import type {HistoryPoint} from "$lib/api/history/history_repository";
import {
    firstIndexFrom,
    HEATMAP_MAX_DWELL_MS,
    HEATMAP_MIN_DWELL_MS,
    heatmapColors,
    heatmapData,
    heatmapRange,
    type HeatmapFocus,
} from "./heatmap_features";

const range = (start: number, end: number) => ({start: new Date(start), end: new Date(end)});

function focus(partial: Partial<HeatmapFocus> = {}): HeatmapFocus {
    return {window: null, selection: null, ...partial};
}

function point(timestamp: number, longitude: number, latitude: number): HistoryPoint {
    return {
        timestamp,
        latitude,
        longitude,
        location_accuracy: 5,
        bearing: 0,
        bearing_accuracy: null,
        battery: null,
        is_raw: false,
    };
}

const MINUTE = 60_000;

describe("heatmapRange", () => {
    it("covers everything without a window", () => {
        expect(heatmapRange(focus())).toBeNull();
    });

    it("is the window while nothing is marked", () => {
        expect(heatmapRange(focus({window: range(100, 200)}))).toEqual({start: 100, end: 200});
    });

    it("is the marked range, cut to the window", () => {
        expect(heatmapRange(focus({window: range(100, 200), selection: range(150, 300)})))
            .toEqual({start: 150, end: 200});
    });
});

describe("firstIndexFrom", () => {
    const points = [point(10, 0, 0), point(20, 0, 0), point(30, 0, 0)];

    it("finds the first point at or after a moment", () => {
        expect(firstIndexFrom(points, 0)).toBe(0);
        expect(firstIndexFrom(points, 20)).toBe(1);
        expect(firstIndexFrom(points, 21)).toBe(2);
        expect(firstIndexFrom(points, 31)).toBe(3);
    });
});

describe("heatmapData", () => {
    it("is empty for an empty history", () => {
        expect(heatmapData([], focus()).features).toEqual([]);
    });

    it("weighs a place by the time spent there, not by its points", () => {
        // A long stay recorded as two points, then a quick pass recorded as many.
        const home = [point(0, 13, 51), point(9 * MINUTE, 13, 51)];
        const road = Array.from({length: 20}, (_, i) => point(10 * MINUTE + i * 6_000, 14, 52));
        const {features} = heatmapData([...home, ...road], focus());

        const at = (lng: number) => features.find((f) => f.geometry.coordinates[0] === lng)!;
        expect(at(13).properties.weight).toBe(1);
        expect(at(14).properties.weight).toBeLessThan(1);
        expect(at(14).properties.weight).toBeGreaterThan(0);
    });

    it("lets no single point count for longer than the cap", () => {
        // The last point before a day without recording, and one that stayed the cap.
        const points = [point(0, 13, 51), point(HEATMAP_MAX_DWELL_MS, 14, 52), point(2 * HEATMAP_MAX_DWELL_MS, 15, 53), point(24 * 60 * MINUTE, 16, 54)];
        const {features} = heatmapData(points, focus());

        const weights = features.map((f) => f.properties.weight);
        expect(weights.filter((weight) => weight === 1)).toHaveLength(3);
        expect(Math.min(...weights)).toBeCloseTo(Math.sqrt(HEATMAP_MIN_DWELL_MS / HEATMAP_MAX_DWELL_MS));
    });

    it("leaves out what lies outside the window and the marked range", () => {
        const points = [point(0, 10, 50), point(100, 11, 50), point(200, 12, 50), point(300, 13, 50)];

        const inWindow = heatmapData(points, focus({window: range(100, 200)}));
        expect(inWindow.features.map((f) => f.geometry.coordinates[0]).sort()).toEqual([11, 12]);

        const marked = heatmapData(points, focus({window: range(0, 300), selection: range(150, 250)}));
        expect(marked.features.map((f) => f.geometry.coordinates[0])).toEqual([12]);
    });

    it("sums points on the same spot into one cell at their centre", () => {
        const points = [point(0, 13.00001, 51.00001), point(MINUTE, 13.00003, 51.00003), point(2 * MINUTE, 13.00005, 51.00005)];
        const {features} = heatmapData(points, focus());

        expect(features).toHaveLength(1);
        expect(features[0].geometry.coordinates[0]).toBeGreaterThan(13.00001);
        expect(features[0].geometry.coordinates[0]).toBeLessThan(13.00005);
    });

    it("coarsens the grid until it fits the budget", () => {
        const points = Array.from({length: 1_000}, (_, i) => point(i * MINUTE, 13 + i * 0.0002, 51));
        const {features} = heatmapData(points, focus(), 100);

        expect(features.length).toBeLessThanOrEqual(100);
        expect(features.length).toBeGreaterThan(10);
    });
});

describe("heatmapColors", () => {
    it("is transparent where nothing was and ends opaque, in both themes", () => {
        for (const dark of [false, true]) {
            const stops = heatmapColors(dark);
            expect(stops[0][0]).toBe(0);
            expect(stops[0][1]).toMatch(/, 0\)$/);
            expect(stops[stops.length - 1][0]).toBe(1);
        }
    });
});
