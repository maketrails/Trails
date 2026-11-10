import {describe, expect, it} from "vitest";
import {availableShortcuts, isShortcutActive, shortcutRange, shortcutSpan} from "./range_shortcuts";

const H = 3_600;
// Local time, so "today" means the same thing in every time zone the tests run in.
const afternoon = new Date(2026, 5, 15, 15, 0, 0);
const earlyMorning = new Date(2026, 5, 15, 2, 0, 0);

describe("availableShortcuts", () => {
    it("offers everything for an own device or an unlimited share", () => {
        const all = ["last_6_hours", "last_24_hours", "today", "last_week", "all"];
        expect(availableShortcuts(null, afternoon)).toEqual(all);
        expect(availableShortcuts(-1, afternoon)).toEqual(all);
    });

    it("limits a share granting 24 hours to what fits in them", () => {
        expect(availableShortcuts(24 * H, afternoon)).toEqual(["last_6_hours", "last_24_hours", "today", "all"]);
    });

    it("offers everything to a share granting a week, but nothing longer than a grant of two days", () => {
        expect(availableShortcuts(7 * 24 * H, afternoon)).toEqual(["last_6_hours", "last_24_hours", "today", "last_week", "all"]);
        expect(availableShortcuts(2 * 24 * H, afternoon)).not.toContain("last_week");
    });

    it("hides today when the grant is shorter than the time since midnight", () => {
        expect(availableShortcuts(6 * H, afternoon)).toEqual(["last_6_hours", "all"]);
        expect(availableShortcuts(6 * H, earlyMorning)).toEqual(["last_6_hours", "today", "all"]);
    });

    it("always offers all", () => {
        expect(availableShortcuts(H, afternoon)).toEqual(["all"]);
    });
});

describe("shortcutRange", () => {
    const oldest = new Date(2026, 5, 1);

    it("ends now and reaches back from there", () => {
        const range = shortcutRange("last_6_hours", afternoon, oldest, afternoon);
        expect(range.end).toEqual(afternoon);
        expect(range.start).toEqual(new Date(2026, 5, 15, 9, 0, 0));
    });

    it("starts at local midnight for today", () => {
        expect(shortcutRange("today", afternoon, oldest, afternoon).start).toEqual(new Date(2026, 5, 15));
        expect(shortcutSpan("today", afternoon)).toBe(15 * 3_600_000);
    });

    it("clamps to the data", () => {
        const recent = new Date(2026, 5, 15, 12, 0, 0);
        expect(shortcutRange("last_week", afternoon, recent, afternoon)).toEqual({start: recent, end: afternoon});
        expect(shortcutRange("all", afternoon, oldest, afternoon)).toEqual({start: oldest, end: afternoon});
    });

    it("stays inside the data when it ended before now", () => {
        const newest = new Date(2026, 5, 15, 10, 0, 0);
        const range = shortcutRange("last_6_hours", afternoon, oldest, newest);
        expect(range.end).toEqual(newest);
        expect(range.end.getTime() - range.start.getTime()).toBe(6 * 3_600_000);
    });
});

describe("isShortcutActive", () => {
    const oldest = new Date(2026, 5, 1);

    it("recognises the window a shortcut set", () => {
        const view = shortcutRange("last_24_hours", afternoon, oldest, afternoon);
        expect(isShortcutActive("last_24_hours", view, afternoon, oldest, afternoon)).toBe(true);
        expect(isShortcutActive("last_6_hours", view, afternoon, oldest, afternoon)).toBe(false);
        expect(isShortcutActive("last_6_hours", null, afternoon, oldest, afternoon)).toBe(false);
    });
});
