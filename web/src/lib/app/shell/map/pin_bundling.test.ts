import {describe, expect, it} from "vitest";
import {bundleOverlappingPins, PIN_WIDTH, type PinPoint} from "./pin_bundling";

const bundle = (points: PinPoint[]) =>
    bundleOverlappingPins(points, (point) => point).map((group) => group.items);

describe("bundleOverlappingPins", () => {
    it("bundles pins lying on top of each other", () => {
        const a = {x: 0, y: 0};
        const b = {x: 10, y: 5};
        expect(bundle([a, b])).toEqual([[a, b]]);
    });

    it("keeps pins apart that merely touch", () => {
        const a = {x: 0, y: 0};
        const b = {x: PIN_WIDTH * 0.6, y: 0};
        expect(bundle([a, b])).toEqual([[a], [b]]);
    });

    it("does not let a growing bundle swallow a chain of neighbours", () => {
        // Each pin sits close enough to the last for the wider pill to reach it, but
        // the row as a whole spans far more than a pin.
        const row = [0, 20, 40, 60, 80, 100, 120].map((x) => ({x, y: 0}));
        const span = (group: PinPoint[]) =>
            Math.max(...group.map((p) => p.x)) - Math.min(...group.map((p) => p.x));

        const groups = bundle(row);
        expect(groups.length).toBeGreaterThan(1);
        for (const group of groups) expect(span(group)).toBeLessThanOrEqual(PIN_WIDTH);
    });

    it("joins a pin to the closer of two neighbours, whatever the order", () => {
        const left = {x: 0, y: 0};
        const middle = {x: 18, y: 0};
        const right = {x: 30, y: 0};
        const far = {x: 75, y: 0};

        for (const order of [[left, middle, right, far], [far, right, middle, left]]) {
            const groups = bundle(order);
            const withFar = groups.find((group) => group.includes(far))!;
            expect(withFar).not.toContain(left);
        }
    });

    it("reports how far a bundle reaches on screen", () => {
        const [only] = bundleOverlappingPins([{x: 0, y: 0}, {x: 6, y: 8}], (point) => point);
        expect(only.span).toBe(10);
    });
});
