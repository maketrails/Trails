import {describe, expect, it} from "vitest";
import {mapStyleUrl} from "./map_style";

describe("mapStyleUrl", () => {
    it("uses the detailed styles outside the heatmap", () => {
        expect(mapStyleUrl(false, false)).toBe("mapbox://styles/mapbox/standard");
        expect(mapStyleUrl(true, false)).toBe("mapbox://styles/mapbox/traffic-night-v2");
    });

    it("uses the reduced styles under a heatmap", () => {
        expect(mapStyleUrl(false, true)).toBe("mapbox://styles/mapbox/light-v11");
        expect(mapStyleUrl(true, true)).toBe("mapbox://styles/mapbox/dark-v11");
    });
});
