import {render, screen, within} from "@testing-library/svelte";
import {describe, expect, it} from "vitest";
import type {MovementItem} from "$lib/api/history/history_repository";
import {TIMELINE_PICK} from "$lib/components/timeline";
import MovementLane from "./MovementLane.svelte";

const HOUR = 60 * 60 * 1_000;

/** One hour across 1000 px: 3.6 s per pixel. */
const scale = {start: 0, end: HOUR, width: 1_000};

function movement(id: string, from: number, to: number, type: string, distance = 3_880): MovementItem {
    return {id, from, to, type, distance_meters: distance};
}

function blocks() {
    return within(screen.getByRole("list", {name: "How the device moved"})).queryAllByRole("listitem");
}

describe("MovementLane", () => {
    it("places each movement where the timeline puts its moments", () => {
        render(MovementLane, {movements: [movement("a", HOUR / 4, HOUR / 2, "cycling")], scale});

        const [block] = blocks();
        expect(block.style.left).toBe("250px");
        expect(block.style.width).toBe("250px");
    });

    it("labels a wide block with its type and distance", () => {
        render(MovementLane, {movements: [movement("a", 0, HOUR / 2, "cycling")], scale});

        expect(blocks()[0]).toHaveTextContent("Cycling · 3.9 km");
        expect(blocks()[0].querySelector("svg")).not.toBeNull();
    });

    it("shows a narrow block as its colour only, as wide as the movement it stands for", () => {
        // 36 s at 3.6 s per pixel: 10 px, too narrow for padding, icon or label.
        render(MovementLane, {movements: [movement("a", 0, 36_000, "walking")], scale});

        const [block] = blocks();
        expect(block.style.width).toBe("10px");
        expect(block).not.toHaveClass("px-2");
        expect(block.querySelector("svg")).toBeNull();
        expect(block).toHaveTextContent("");
    });

    it("clips a movement running off the window and leaves out what is not in it", () => {
        render(MovementLane, {
            movements: [movement("before", -2 * HOUR, -HOUR, "walking"), movement("across", -HOUR, HOUR / 10, "travel")],
            scale,
        });

        expect(blocks()).toHaveLength(1);
        expect(blocks()[0].style.left).toBe("0px");
        expect(blocks()[0].style.width).toBe("100px");
    });

    it("names a type it does not know as unknown", () => {
        render(MovementLane, {movements: [movement("a", 0, HOUR / 2, "sailing")], scale});

        expect(blocks()[0]).toHaveTextContent("Unknown");
    });

    it("lets the timeline pick the movement's stretch", () => {
        render(MovementLane, {movements: [movement("a", 1_000, 2_000_000, "travel")], scale});

        expect(blocks()[0]).toHaveAttribute(TIMELINE_PICK, "1000,2000000");
    });
});
