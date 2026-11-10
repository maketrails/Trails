import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {
    parseTrailDisplayMode,
    readStoredTrailDisplayMode,
    setTrailDisplayMode,
    trailDisplayMode,
    TRAIL_DISPLAY_MODE_STORAGE_KEY,
} from "./trail_display_mode.svelte";

describe("parseTrailDisplayMode", () => {
    it("accepts every mode", () => {
        expect(parseTrailDisplayMode("none")).toBe("none");
        expect(parseTrailDisplayMode("trail")).toBe("trail");
        expect(parseTrailDisplayMode("heatmap")).toBe("heatmap");
    });

    it("falls back to the trail for anything else", () => {
        expect(parseTrailDisplayMode(null)).toBe("trail");
        expect(parseTrailDisplayMode("lines")).toBe("trail");
        expect(parseTrailDisplayMode(3)).toBe("trail");
    });
});

describe("trail display mode", () => {
    beforeEach(() => localStorage.clear());
    afterEach(() => {
        vi.restoreAllMocks();
        setTrailDisplayMode("trail");
    });

    it("remembers the chosen mode", () => {
        setTrailDisplayMode("heatmap");

        expect(trailDisplayMode.mode).toBe("heatmap");
        expect(localStorage.getItem(TRAIL_DISPLAY_MODE_STORAGE_KEY)).toBe("heatmap");
        expect(readStoredTrailDisplayMode()).toBe("heatmap");
    });

    it("reads the default from empty storage", () => {
        expect(readStoredTrailDisplayMode()).toBe("trail");
    });

    it("keeps working when storage throws", () => {
        vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
            throw new Error("blocked");
        });
        vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
            throw new Error("blocked");
        });

        expect(readStoredTrailDisplayMode()).toBe("trail");
        setTrailDisplayMode("none");
        expect(trailDisplayMode.mode).toBe("none");
    });
});
