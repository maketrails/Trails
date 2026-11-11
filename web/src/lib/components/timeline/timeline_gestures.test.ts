import {describe, expect, it} from "vitest";
import {flingStep, LONG_PRESS_MS, MIN_FLING_VELOCITY, TOUCH_SLOP, TouchRecognizer, velocityOf} from "./timeline_gestures";

describe("TouchRecognizer", () => {
    it("reports a finger that goes down and up in place as a tap", () => {
        const touch = new TouchRecognizer();

        expect(touch.down(1, 100, 50, 0)).toEqual([{type: "stop"}]);
        expect(touch.move(1, 103, 52, 40)).toEqual([]);
        expect(touch.up(1, 80)).toEqual([{type: "tap", x: 100}]);
    });

    it("pans with one finger once it leaves the slop, keeping the content under it", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);

        expect(touch.move(1, 100 + TOUCH_SLOP + 5, 50, 20)).toEqual([{type: "pan", pixels: -(TOUCH_SLOP + 5)}]);
        expect(touch.move(1, 130, 50, 40)).toEqual([{type: "pan", pixels: -15}]);
        expect(touch.awaitingLongPress).toBe(false);
    });

    it("pans on a mostly vertical swipe too, using only its sideways travel", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);

        expect(touch.move(1, 104, 80, 20)).toEqual([{type: "pan", pixels: -4}]);
    });

    it("does not tap or fling after a cancelled pan", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.move(1, 160, 50, 10);

        expect(touch.cancel(1, 20)).toEqual([]);
    });

    it("flings a pan that is let go of while moving", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.move(1, 120, 50, 10);
        touch.move(1, 140, 50, 20);
        touch.move(1, 160, 50, 30);

        const [fling] = touch.up(1, 32);

        expect(fling).toEqual({type: "fling", velocity: 2});
    });

    it("lets a pan that came to rest stop where it is", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.move(1, 160, 50, 10);

        expect(touch.up(1, 500)).toEqual([]);
    });

    it("starts marking a range after a long press, and follows the finger", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);

        expect(touch.longPress(LONG_PRESS_MS - 1)).toEqual([]);
        expect(touch.longPress(LONG_PRESS_MS)).toEqual([{type: "select-start", x: 100}]);
        expect(touch.move(1, 101, 50, 500)).toEqual([{type: "select-move", x: 101}]);
        expect(touch.up(1, 600)).toEqual([{type: "select-end"}]);
    });

    it("never turns a pan into a long press", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.move(1, 150, 50, 10);

        expect(touch.longPress(LONG_PRESS_MS * 2)).toEqual([]);
    });

    it("zooms around the centre of a pinch, panning by the centre's travel first", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        expect(touch.down(2, 200, 50, 5)).toEqual([]);

        // The right finger moves out by 100 px: twice the distance, centre 50 px right.
        expect(touch.move(2, 300, 50, 20)).toEqual([
            {type: "pan", pixels: -50},
            {type: "zoom", factor: 0.5, centre: 200},
        ]);
    });

    it("finishes a range being marked when a second finger lands", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.longPress(LONG_PRESS_MS);

        expect(touch.down(2, 200, 50, 500)).toEqual([{type: "select-end"}]);
    });

    it("pans with the finger left over after a pinch, without a jump and without a tap", () => {
        const touch = new TouchRecognizer();
        touch.down(1, 100, 50, 0);
        touch.down(2, 200, 50, 0);
        touch.move(2, 300, 50, 10);

        expect(touch.up(2, 20)).toEqual([]);
        expect(touch.move(1, 110, 50, 30)).toEqual([{type: "pan", pixels: -10}]);
        expect(touch.up(1, 600)).toEqual([]);
    });

    it("ignores fingers it never saw go down", () => {
        const touch = new TouchRecognizer();

        expect(touch.move(7, 10, 10, 0)).toEqual([]);
        expect(touch.up(7, 0)).toEqual([]);
    });
});

describe("velocityOf", () => {
    it("measures over the most recent stretch only", () => {
        const samples = [{x: 0, t: 0}, {x: 0, t: 200}, {x: 50, t: 250}];

        expect(velocityOf(samples, 250)).toBe(1);
    });

    it("is zero once the finger has rested", () => {
        expect(velocityOf([{x: 0, t: 0}, {x: 50, t: 10}], 300)).toBe(0);
    });
});

describe("flingStep", () => {
    it("coasts the way the finger was going, scrolling the other way", () => {
        const step = flingStep(1, 16);

        expect(step.pixels).toBeLessThan(0);
        expect(step.velocity).toBeLessThan(1);
        expect(step.velocity).toBeGreaterThan(0);
    });

    it("covers the same ground however the frames are cut", () => {
        const whole = flingStep(2, 32);
        const first = flingStep(2, 16);
        const second = flingStep(first.velocity, 16);

        expect(first.pixels + second.pixels).toBeCloseTo(whole.pixels, 6);
    });

    it("comes to a stop", () => {
        let velocity = MIN_FLING_VELOCITY * 10;
        let frames = 0;
        while (velocity !== 0 && frames < 1_000) {
            velocity = flingStep(velocity, 16).velocity;
            frames++;
        }

        expect(velocity).toBe(0);
        expect(frames).toBeLessThan(200);
    });
});
