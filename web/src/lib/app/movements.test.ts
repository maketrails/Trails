import {describe, expect, it} from "vitest";
import type {MovementItem} from "$lib/api/history/history_repository";
import {formatDistance, movementAt, movementTypeOf} from "./movements";

function movement(from: number, to: number, type = "walking"): MovementItem {
    return {id: `${from}`, from, to, type, distance_meters: 100};
}

describe("movementTypeOf", () => {
    it("knows the three types the server sends", () => {
        expect(movementTypeOf(movement(0, 1, "walking"))).toBe("walking");
        expect(movementTypeOf(movement(0, 1, "cycling"))).toBe("cycling");
        expect(movementTypeOf(movement(0, 1, "travel"))).toBe("travel");
    });

    it("shows a type added later as unknown instead of guessing", () => {
        expect(movementTypeOf(movement(0, 1, "sailing"))).toBeNull();
    });
});

describe("movementAt", () => {
    const movements = [movement(0, 100), movement(200, 300), movement(400, 500)];

    it("finds the movement a moment falls into, both ends included", () => {
        expect(movementAt(movements, 0)?.from).toBe(0);
        expect(movementAt(movements, 250)?.from).toBe(200);
        expect(movementAt(movements, 500)?.from).toBe(400);
    });

    it("finds nothing between, before or after the movements", () => {
        expect(movementAt(movements, 150)).toBeNull();
        expect(movementAt(movements, -1)).toBeNull();
        expect(movementAt(movements, 501)).toBeNull();
        expect(movementAt([], 0)).toBeNull();
    });
});

describe("formatDistance", () => {
    it("counts metres below a kilometre and kilometres from there on", () => {
        expect(formatDistance(740, "en")).toBe("740 m");
        expect(formatDistance(3_880, "en")).toBe("3.9 km");
    });

    it("follows the locale", () => {
        expect(formatDistance(3_880, "de")).toBe("3,9 km");
    });
});
