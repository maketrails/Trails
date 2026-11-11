import {describe, expect, it} from "vitest";
import {createLabHistory, daysOf, episodeAt, formatDuration} from "./lab_history";

// A Wednesday afternoon, so the week holds workdays, a weekend and the gap.
const NOW = new Date(2026, 10, 11, 15, 20);

describe("createLabHistory", () => {
    const history = createLabHistory(NOW);

    it("covers every moment from the first to the last one, without overlaps", () => {
        expect(history.episodes[0].from).toBe(history.oldest);
        expect(history.episodes[history.episodes.length - 1].to).toBe(history.newest);
        for (let index = 1; index < history.episodes.length; index++) {
            expect(history.episodes[index].from).toBe(history.episodes[index - 1].to);
            expect(history.episodes[index].to).toBeGreaterThan(history.episodes[index].from);
        }
    });

    it("holds all three ways of moving, stays and a gap", () => {
        const types = new Set(history.movements.map((movement) => movement.type));
        expect([...types].sort()).toEqual(["cycling", "travel", "walking"]);
        expect(history.episodes.some((episode) => episode.kind === "stay")).toBe(true);
        expect(history.episodes.filter((episode) => episode.kind === "gap")).toHaveLength(1);
    });

    it("is the same on every load", () => {
        expect(createLabHistory(NOW)).toEqual(history);
    });

    it("answers which episode a moment falls into", () => {
        expect(episodeAt(history.episodes, history.oldest)?.kind).toBe("stay");
        expect(episodeAt(history.episodes, history.newest)).not.toBeNull();
        expect(episodeAt(history.episodes, history.newest + 1)).toBeNull();
    });
});

describe("daysOf", () => {
    it("lists each local day a stretch touches", () => {
        const days = daysOf(new Date(2026, 10, 5).getTime(), NOW.getTime());
        expect(days).toHaveLength(7);
        expect(days[6].start).toBe(new Date(2026, 10, 11).getTime());
    });
});

describe("formatDuration", () => {
    it("reads in hours and minutes", () => {
        expect(formatDuration(5 * 60_000, "en")).toBe("5 min");
        expect(formatDuration(125 * 60_000, "en")).toBe("2 hr 5 min");
    });
});
