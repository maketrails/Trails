import {fireEvent, render, screen} from "@testing-library/svelte";
import {afterAll, beforeAll, describe, expect, it} from "vitest";
import TimelinePickHarness from "./TimelinePickHarness.svelte";

const oldestPoint = new Date("2026-09-29T00:00:00Z");
const newestPoint = new Date("2026-09-30T00:00:00Z");
const pick = {start: Date.parse("2026-09-29T15:24:00Z"), end: Date.parse("2026-09-29T15:35:00Z")};

// jsdom lays nothing out; the track needs a width to open its window on.
let clientWidth: PropertyDescriptor | undefined;
beforeAll(() => {
    clientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
    Object.defineProperty(HTMLElement.prototype, "clientWidth", {configurable: true, get: () => 1_000});
});
afterAll(() => {
    if (clientWidth != null) Object.defineProperty(HTMLElement.prototype, "clientWidth", clientWidth);
});

/** A press that goes nowhere: what a click on the track is to the timeline. */
async function click(target: Element) {
    await fireEvent.pointerDown(target, {pointerId: 1, clientX: 500});
    await fireEvent.pointerUp(target, {pointerId: 1, clientX: 500});
}

describe("Timeline", () => {
    it("marks the stretch a lane block stands for when it is clicked", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick});

        await click(screen.getByTestId("block"));

        expect(screen.getByTestId("selection")).toHaveTextContent(`${pick.start},${pick.end}`);
    });

    it("drops a marked range again on a click anywhere else on the track", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick});
        await click(screen.getByTestId("block"));

        await click(screen.getByRole("region", {name: "History timeline"}));

        expect(screen.getByTestId("selection")).toHaveTextContent("none");
    });
});
