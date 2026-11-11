import {fireEvent, render, screen} from "@testing-library/svelte";
import {describe, expect, it} from "vitest";
import LabPage from "../../../routes/lab/timeline/+page.svelte";

// A smoke test: the lab is a prototype, but it should at least open every proposal.
describe("timeline lab", () => {
    it("opens on the day strip and switches between the proposals", async () => {
        render(LabPage);
        expect(screen.getByText("Experimental")).toBeInTheDocument();
        expect(screen.getByRole("list", {name: "Days"})).toBeInTheDocument();

        await fireEvent.click(screen.getAllByRole("button", {expanded: false})[0]);
        expect(screen.getByText(/on the move/)).toBeInTheDocument();

        await fireEvent.click(screen.getByRole("tab", {name: /Scrubber/}));
        expect(screen.getByRole("region", {name: "Detail"})).toBeInTheDocument();

        await fireEvent.click(screen.getByRole("tab", {name: /Story/}));
        expect(screen.getByRole("slider", {name: "Where the list is in the range"})).toBeInTheDocument();
        expect(screen.getAllByText(/Home/).length).toBeGreaterThan(0);
    });
});
