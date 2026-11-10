/**
 * The quick ranges offered above the timeline — the last hours, today, the last week,
 * everything — and the rules for which of them make sense to show and where they land.
 * Pure on purpose: the clock and the browser's time zone come in through [now].
 */

/** One quick range. `all` is everything the viewer may see. */
export type RangeShortcut = "last_6_hours" | "last_24_hours" | "today" | "last_week" | "all";

/** The order the shortcuts are offered in. */
export const RANGE_SHORTCUTS: readonly RangeShortcut[] = ["last_6_hours", "last_24_hours", "today", "last_week", "all"];

const HOUR = 60 * 60 * 1_000;

/** Two views closer than this, at either end, count as the same range. */
const MATCH_TOLERANCE = 60 * 1_000;

/**
 * How far back [shortcut] reaches from [now], in milliseconds, or `null` for `all`.
 * "Today" runs from local midnight, so it is as long as the day is old.
 */
export function shortcutSpan(shortcut: RangeShortcut, now: Date): number | null {
    switch (shortcut) {
        case "last_6_hours":
            return 6 * HOUR;
        case "last_24_hours":
            return 24 * HOUR;
        case "last_week":
            return 7 * 24 * HOUR;
        case "today":
            return now.getTime() - new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        case "all":
            return null;
    }
}

/**
 * The shortcuts worth showing. [grantSeconds] is how far back the viewer may read:
 * `null` (or a negative value) when nothing is cut off — an own device, an unlimited
 * share. A bounded shortcut is shown only if its whole period lies within the grant;
 * `all` is always shown, since it means whatever is granted.
 */
export function availableShortcuts(grantSeconds: number | null, now: Date): RangeShortcut[] {
    const unlimited = grantSeconds == null || grantSeconds < 0;
    return RANGE_SHORTCUTS.filter((shortcut) => {
        const span = shortcutSpan(shortcut, now);
        return span == null || unlimited || span <= grantSeconds * 1_000;
    });
}

/**
 * The window [shortcut] stands for: ending now and reaching back its span, moved and
 * shortened as needed to lie within the data ([oldest]…[newest]), the way the timeline
 * itself clamps a window.
 */
export function shortcutRange(
    shortcut: RangeShortcut,
    now: Date,
    oldest: Date,
    newest: Date,
): {start: Date; end: Date} {
    const available = Math.max(newest.getTime() - oldest.getTime(), 1);
    const wanted = shortcutSpan(shortcut, now) ?? available;
    const length = Math.min(Math.max(wanted, 1), available);
    const start = Math.min(Math.max(now.getTime() - wanted, oldest.getTime()), newest.getTime() - length);
    return {start: new Date(start), end: new Date(start + length)};
}

/** Whether [view] is the window [shortcut] would set. */
export function isShortcutActive(
    shortcut: RangeShortcut,
    view: {start: Date; end: Date} | null,
    now: Date,
    oldest: Date,
    newest: Date,
): boolean {
    if (view == null) return false;
    const range = shortcutRange(shortcut, now, oldest, newest);
    return Math.abs(view.start.getTime() - range.start.getTime()) <= MATCH_TOLERANCE
        && Math.abs(view.end.getTime() - range.end.getTime()) <= MATCH_TOLERANCE;
}
