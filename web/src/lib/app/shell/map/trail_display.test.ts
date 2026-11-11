import {describe, expect, it} from "vitest";
import type {HistoryPoint} from "$lib/api/history/history_repository";
import {createTrailSource, segmentCrosses, type TrailBounds} from "./trail_display";

const VIEW: TrailBounds = [0, 0, 1, 1];

function point(index: number, longitude: number, latitude: number): HistoryPoint {
    return {
        timestamp: index * 1_000,
        latitude,
        longitude,
        location_accuracy: 5,
        bearing: 0,
        bearing_accuracy: null,
        battery: null,
        is_raw: false,
    };
}

const track = (coordinates: [number, number][]) => coordinates.map(([lng, lat], i) => point(i, lng, lat));

/** Every point, unclipped and unthinned, cut to [bounds]. */
const clipped = (points: HistoryPoint[], bounds: TrailBounds) =>
    createTrailSource(points).drawnFor({tolerance: 0, bounds});

describe("segmentCrosses", () => {
    it("counts a stretch with an end inside", () => {
        expect(segmentCrosses(VIEW, 0.5, 0.5, 5, 5)).toBe(true);
    });

    it("counts a stretch cutting straight through with both ends outside", () => {
        expect(segmentCrosses(VIEW, -1, 0.5, 2, 0.5)).toBe(true);
        expect(segmentCrosses(VIEW, 0.5, -1, 0.5, 2)).toBe(true);
        expect(segmentCrosses(VIEW, -1, -1, 2, 2)).toBe(true);
    });

    it("counts a stretch clipping a corner", () => {
        expect(segmentCrosses(VIEW, -0.1, 0.8, 0.3, 1.2)).toBe(true);
    });

    it("leaves out a stretch passing by a corner", () => {
        expect(segmentCrosses(VIEW, -0.5, 0.6, 0.6, 1.8)).toBe(false);
    });

    it("leaves out a stretch beyond one edge", () => {
        expect(segmentCrosses(VIEW, -2, -1, -1, 3)).toBe(false);
        expect(segmentCrosses(VIEW, 0.5, 1.5, 0.8, 2)).toBe(false);
    });
});

describe("clipping a trail to the view", () => {
    it("keeps a stretch that crosses the view without a point inside it", () => {
        const drawn = clipped(track([[-3, 0.5], [-2, 0.5], [2, 0.5], [3, 0.5]]), VIEW);

        expect(drawn.coordinates).toEqual([[-2, 0.5], [2, 0.5]]);
        expect(Array.from(drawn.breaks)).toEqual([0, 0]);
    });

    it("keeps the points just outside around a stretch inside", () => {
        const drawn = clipped(track([[-3, 0.5], [-1, 0.5], [0.5, 0.5], [2, 0.5], [3, 0.5]]), VIEW);

        expect(drawn.coordinates).toEqual([[-1, 0.5], [0.5, 0.5], [2, 0.5]]);
    });

    it("breaks the line where it leaves the view and comes back", () => {
        const drawn = clipped(track([[0.5, 0.5], [2, 0.5], [5, 5], [6, 6], [2, 0.6], [0.6, 0.6]]), VIEW);

        expect(drawn.coordinates).toEqual([[0.5, 0.5], [2, 0.5], [2, 0.6], [0.6, 0.6]]);
        expect(Array.from(drawn.breaks)).toEqual([0, 0, 1, 0]);
    });

    it("keeps a crossing stretch that spans two chunks", () => {
        // 64 points far west, then 64 far east: the one stretch across the view joins
        // the last point of the first chunk to the first point of the second.
        const coordinates: [number, number][] = [];
        for (let i = 0; i < 64; i++) coordinates.push([-50 - i * 0.01, 0.5]);
        for (let i = 0; i < 64; i++) coordinates.push([50 + i * 0.01, 0.5]);

        const drawn = clipped(track(coordinates), VIEW);

        expect(drawn.coordinates).toEqual([coordinates[63], coordinates[64]]);
        expect(Array.from(drawn.breaks)).toEqual([0, 0]);
    });

    it("draws nothing of a trail that stays off screen", () => {
        const drawn = clipped(track([[-3, 3], [-2, 3], [2, 3], [3, 3]]), VIEW);

        expect(drawn.coordinates).toEqual([]);
    });
});
