import {describe, expect, it} from "vitest";
import type {MovementItem} from "$lib/api/history/history_repository";
import {bandOf, isHighlighted, movementFlags, trailData, type TrailFocus} from "./trail_features";

const range = (start: number, end: number) => ({start: new Date(start), end: new Date(end)});

function focus(partial: Partial<TrailFocus>): TrailFocus {
    return {window: null, selection: null, movements: [], ...partial};
}

function movement(from: number, to: number, type: string): MovementItem {
    return {id: `${from}`, from, to, type, distance_meters: 100};
}

describe("bandOf", () => {
    it("shows the whole trail without a window", () => {
        expect(bandOf(5, focus({}))).toBe("window");
    });

    it("leaves out what lies outside the window", () => {
        const window = range(100, 200);

        expect(bandOf(99, focus({window}))).toBe("outside");
        expect(bandOf(150, focus({window}))).toBe("window");
        expect(bandOf(201, focus({window}))).toBe("outside");
    });

    it("tells the marked range from the rest of the window", () => {
        const current = focus({window: range(100, 200), selection: range(140, 160)});

        expect(bandOf(120, current)).toBe("unmarked");
        expect(bandOf(150, current)).toBe("marked");
        expect(bandOf(180, current)).toBe("unmarked");
        expect(bandOf(250, current)).toBe("outside");
    });

    it("lets the puck go wherever the line is drawn", () => {
        const current = focus({window: range(100, 200), selection: range(140, 160)});

        expect(isHighlighted(120, current)).toBe(true);
        expect(isHighlighted(250, current)).toBe(false);
    });
});

describe("movementFlags", () => {
    const movements = [movement(10, 30, "walking"), movement(40, 70, "cycling")];

    it("gives every stretch inside a movement its type", () => {
        const flags = movementFlags([10, 20, 30, 40, 50, 70], movements);

        expect(flags).toEqual([null, "walking", "walking", null, "cycling", "cycling"]);
    });

    it("leaves a stretch reaching from one movement into the next without one", () => {
        expect(movementFlags([25, 45], movements)).toEqual([null, null]);
    });

    it("leaves a type it does not know without one", () => {
        expect(movementFlags([0, 5], [movement(0, 5, "sailing")])).toEqual([null, null]);
    });
});

describe("trailData", () => {
    it("cuts a new feature wherever the movement changes", () => {
        const coordinates = [[0, 0], [1, 0], [2, 0], [3, 0]];
        const data = trailData(coordinates, [], [], [], [], [null, "walking", "walking", "cycling"]);

        expect(data.features.map((f) => [f.properties.movement, f.geometry.coordinates.length])).toEqual([
            ["walking", 3],
            ["cycling", 2],
        ]);
    });

    it("keeps the runs joined at their shared point", () => {
        const coordinates = [[0, 0], [1, 0], [2, 0]];
        const data = trailData(coordinates, [], [], [], [], [null, "walking", "cycling"]);

        expect(data.features[0].geometry.coordinates.at(-1)).toEqual(data.features[1].geometry.coordinates[0]);
    });
});
