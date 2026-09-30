import {render, screen} from "@testing-library/svelte";
import {describe, expect, it} from "vitest";
import type {HistoryPoint, MovementItem} from "$lib/api/history/history_repository";
import TrailPointPopover from "./TrailPointPopover.svelte";

const point: HistoryPoint = {
    timestamp: Date.parse("2026-09-29T15:30:00Z"),
    latitude: 52.4,
    longitude: 13.1,
    location_accuracy: 5,
    bearing: 0,
    bearing_accuracy: null,
    battery: {percentage: 80, is_charging: false},
    is_raw: false,
};

const ride: MovementItem = {
    id: "ride",
    from: Date.parse("2026-09-29T15:24:00Z"),
    to: Date.parse("2026-09-29T15:35:00Z"),
    type: "cycling",
    distance_meters: 3_880,
};

describe("TrailPointPopover", () => {
    it("shows the movement the puck stands on above the position", () => {
        render(TrailPointPopover, {state: {point, movement: ride}});

        expect(screen.getByText("Cycling")).toBeInTheDocument();
        expect(screen.getByText("3.9 km")).toBeInTheDocument();
        expect(screen.getByText(/11 minutes/)).toBeInTheDocument();
    });

    it("shows only the position between movements", () => {
        render(TrailPointPopover, {state: {point, movement: null}});

        expect(screen.queryByText("Cycling")).toBeNull();
        expect(screen.getByText("±5 m")).toBeInTheDocument();
    });

    it("shows nothing without a position", () => {
        const {container} = render(TrailPointPopover, {state: {point: null, movement: ride}});

        expect(container).toHaveTextContent("");
    });
});
