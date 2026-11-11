import {fireEvent, render, screen} from "@testing-library/svelte";
import {afterAll, beforeAll, describe, expect, it} from "vitest";
import {tick} from "svelte";
import TimelineGrowHarness from "./TimelineGrowHarness.svelte";
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

describe("Timeline opening window", () => {
    const day = 24 * 60 * 60 * 1_000;
    const oldest = new Date("2026-01-01T00:00:00Z");
    const at = (days: number) => new Date(oldest.getTime() + days * day);
    const view = () => screen.getByTestId("view").textContent;

    it("opens on the newest week", async () => {
        render(TimelineGrowHarness, {oldestPoint: oldest, newestPoint: at(30)});
        await tick();

        expect(view()).toBe(`${at(23).getTime()},${at(30).getTime()}`);
    });

    it("follows the newest point while the history is still arriving", async () => {
        const {rerender} = render(TimelineGrowHarness, {oldestPoint: oldest, newestPoint: at(2)});
        await tick();

        await rerender({oldestPoint: oldest, newestPoint: at(60)});
        await tick();

        expect(view()).toBe(`${at(53).getTime()},${at(60).getTime()}`);
    });

    it("stays where it was put once it has been moved", async () => {
        const {rerender} = render(TimelineGrowHarness, {oldestPoint: oldest, newestPoint: at(30)});
        await tick();
        await fireEvent.click(screen.getByTestId("move"));
        const moved = view();

        await rerender({oldestPoint: oldest, newestPoint: at(60)});
        await tick();

        expect(view()).toBe(moved);
    });
});

describe("Timeline on touch", () => {
    /** A finger that lands and lifts without moving. */
    async function tap(target: Element, clientX = 500) {
        await fireEvent.pointerDown(target, {pointerId: 1, pointerType: "touch", clientX});
        await fireEvent.pointerUp(target, {pointerId: 1, pointerType: "touch", clientX});
    }

    const track = () => screen.getByRole("region", {name: "History timeline"});

    // jsdom lays nothing out, so every tap on the track lands in its middle: noon.
    const aroundNoon = {start: Date.parse("2026-09-29T11:00:00Z"), end: Date.parse("2026-09-29T13:00:00Z")};

    it("marks the stretch a lane block stands for when it is tapped", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick});

        await tap(screen.getByTestId("block"));

        expect(screen.getByTestId("selection")).toHaveTextContent(`${pick.start},${pick.end}`);
    });

    it("drops a marked range on a tap outside it", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick});
        await tap(screen.getByTestId("block"));

        await tap(track());

        expect(screen.getByTestId("selection")).toHaveTextContent("none");
    });

    it("keeps a marked range on a tap inside it", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick: aroundNoon});
        await tap(screen.getByTestId("block"));

        await tap(track());

        expect(screen.getByTestId("selection")).toHaveTextContent(`${aroundNoon.start},${aroundNoon.end}`);
    });

    it("pans rather than marks when a finger swipes", async () => {
        render(TimelinePickHarness, {oldestPoint, newestPoint, pick});

        await fireEvent.pointerDown(track(), {pointerId: 1, pointerType: "touch", clientX: 500});
        await fireEvent.pointerMove(track(), {pointerId: 1, pointerType: "touch", clientX: 400});
        await fireEvent.pointerUp(track(), {pointerId: 1, pointerType: "touch", clientX: 400});

        expect(screen.getByTestId("selection")).toHaveTextContent("none");
    });
});
