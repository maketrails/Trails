import {beforeEach, describe, expect, it} from "vitest";
import type {HistoryPoint, MovementItem} from "./history_repository";
import {
    applyFreshMovements,
    clearAllCachedHistories,
    clearCachedSeries,
    readCachedHistory,
    storeCachedHistory,
} from "./history_cache";

function movement(id: string, from: number, to: number, type = "walking"): MovementItem {
    return {id, from, to, type, distance_meters: 100};
}

function point(timestamp: number, isRaw = false): HistoryPoint {
    return {
        timestamp,
        latitude: 52.4,
        longitude: 13.1,
        location_accuracy: 5,
        bearing: 0,
        bearing_accuracy: null,
        battery: null,
        is_raw: isRaw,
    };
}

describe("applyFreshMovements", () => {
    const cached = [movement("a", 0, 100), movement("b", 200, 300), movement("c", 400, 500)];

    it("keeps what it has when a read brought nothing", () => {
        expect(applyFreshMovements(cached, [], null)).toBe(cached);
    });

    it("replaces every movement from the first fresh one on", () => {
        const result = applyFreshMovements(cached, [movement("d", 200, 450, "cycling")], null);

        expect(result.map((m) => m.id)).toEqual(["a", "d"]);
    });

    it("drops a cached movement reaching into a rewritten stretch, so nothing overlaps", () => {
        // The optimizer rewrote from 250 on: "b" (200–300) was cut differently, and the
        // fresh movement only starts at 260.
        const result = applyFreshMovements(cached, [movement("d", 260, 480, "cycling")], 250);

        expect(result.map((m) => m.id)).toEqual(["a", "d"]);
        for (let i = 1; i < result.length; i++) expect(result[i - 1].to).toBeLessThanOrEqual(result[i].from);
    });

    it("drops what a rewrite took away even when it brought no movements", () => {
        expect(applyFreshMovements(cached, [], 350).map((m) => m.id)).toEqual(["a", "b"]);
    });

    it("replaces a movement that grew, coming back under its old id", () => {
        const result = applyFreshMovements(cached, [movement("c", 400, 700)], 600);

        expect(result.map((m) => [m.id, m.to])).toEqual([["a", 100], ["b", 300], ["c", 700]]);
    });

    it("hands the movements out oldest first", () => {
        const result = applyFreshMovements(cached, [movement("x", 150, 180), movement("y", 190, 199)], null);

        expect(result.map((m) => m.from)).toEqual([0, 150, 190]);
    });
});

describe("the cached movements", () => {
    const device = "device-1";

    beforeEach(async () => {
        await clearAllCachedHistories();
    });

    it("are read back with the points they were stored with", async () => {
        await storeCachedHistory(device, "optimized", [point(0), point(100)], {cursor: 1, rebuiltAt: null, resume: null}, {
            fresh: [movement("a", 0, 100)],
            rewrittenFrom: 0,
        });

        const cached = await readCachedHistory(device, "optimized");

        expect(cached?.points.map((p) => p.timestamp)).toEqual([0, 100]);
        expect(cached?.movements).toEqual([movement("a", 0, 100)]);
    });

    it("give way the same way in the cache as in memory", async () => {
        await storeCachedHistory(device, "optimized", [point(0), point(300)], {cursor: 1, rebuiltAt: null, resume: null}, {
            fresh: [movement("a", 0, 100), movement("b", 200, 300)],
            rewrittenFrom: 0,
        });
        await storeCachedHistory(device, "optimized", [point(250), point(480)], {cursor: 2, rebuiltAt: null, resume: null}, {
            fresh: [movement("d", 260, 480, "cycling")],
            rewrittenFrom: 250,
        });

        const cached = await readCachedHistory(device, "optimized");

        expect(cached?.movements.map((m) => m.id)).toEqual(["a", "d"]);
    });

    it("belong to one series and are cleared with it", async () => {
        const progress = {cursor: 1, rebuiltAt: null, resume: null};
        await storeCachedHistory(device, "optimized", [point(0)], progress, {fresh: [movement("a", 0, 100)], rewrittenFrom: 0});
        await storeCachedHistory(device, "raw", [point(0, true)], progress, {fresh: [movement("r", 0, 100)], rewrittenFrom: null});

        await clearCachedSeries(device, "optimized");

        expect(await readCachedHistory(device, "optimized")).toBeNull();
        expect((await readCachedHistory(device, "raw"))?.movements.map((m) => m.id)).toEqual(["r"]);
    });
});
