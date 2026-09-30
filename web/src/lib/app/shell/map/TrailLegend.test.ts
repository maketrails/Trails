import {render, screen, within} from "@testing-library/svelte";
import {describe, expect, it} from "vitest";
import TrailLegend from "./TrailLegend.svelte";

function entries() {
    return within(screen.getByRole("list", {name: "What the colours on the line mean"}))
        .getAllByRole("listitem")
        .map((item) => item.textContent?.trim());
}

describe("TrailLegend", () => {
    it("explains the track and the ways of moving it is coloured by", () => {
        render(TrailLegend);

        expect(entries()).toEqual(["Track", "Walking", "Cycling", "Travel"]);
    });

    it("explains the outline while a range is marked", () => {
        render(TrailLegend, {marked: true});

        expect(entries()).toContain("Marked");
    });
});
