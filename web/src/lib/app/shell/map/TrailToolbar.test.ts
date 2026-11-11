import {fireEvent, render, screen} from "@testing-library/svelte";
import {afterEach, describe, expect, it} from "vitest";
import {setTrailDisplayMode, trailDisplayMode} from "$lib/state/trail_display_mode.svelte";
import TrailToolbar from "./TrailToolbar.svelte";

describe("TrailToolbar", () => {
    afterEach(() => setTrailDisplayMode("trail"));

    it("switches how the history is drawn", async () => {
        render(TrailToolbar);

        expect(screen.getByRole("button", {name: "Trail"}).getAttribute("aria-pressed")).toBe("true");
        await fireEvent.click(screen.getByRole("button", {name: "Heatmap"}));

        expect(trailDisplayMode.mode).toBe("heatmap");
        expect(screen.getByRole("button", {name: "Heatmap"}).getAttribute("aria-pressed")).toBe("true");
    });

    it("offers no source where there is none to choose", () => {
        render(TrailToolbar);

        expect(screen.queryByRole("group", {name: "Data"})).toBeNull();
    });

    it("switches the source without touching the mode", async () => {
        setTrailDisplayMode("none");
        render(TrailToolbar, {source: "optimized"});

        await fireEvent.click(screen.getByRole("button", {name: "Raw data"}));

        expect(screen.getByRole("button", {name: "Raw data"}).getAttribute("aria-pressed")).toBe("true");
        expect(trailDisplayMode.mode).toBe("none");
    });
});
