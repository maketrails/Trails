/**
 * How much location history a share reveals, encoded the way the server stores
 * it (`Share.locationHistorySeconds`):
 * - `0` — no history at all, only the current location.
 * - negative — an unbounded window (the app writes `Duration.INFINITE` this way).
 * - otherwise — a window in seconds.
 *
 * The options mirror the steps the app offers when creating a share, so a share
 * edited in the browser can still be described by the app's slider.
 */
import {t} from "#lib/i18n/index.ts";

export const INFINITE_HISTORY_SECONDS = -1;

export interface LocationHistoryOption {
    seconds: number;
    /** Short label for pickers. */
    label: string;
}

/** The retention steps, in the order the picker offers them. */
const LOCATION_HISTORY_STEPS: readonly { seconds: number; key: string }[] = [
    {seconds: 0, key: "history.preset.current_only"},
    {seconds: 60 * 60, key: "history.preset.last.hour"},
    {seconds: 6 * 60 * 60, key: "history.preset.last.six_hours"},
    {seconds: 24 * 60 * 60, key: "history.preset.last.day"},
    {seconds: 7 * 24 * 60 * 60, key: "history.preset.last.week"},
    {seconds: INFINITE_HISTORY_SECONDS, key: "history.preset.full"},
];

/** The retention steps with their labels resolved in the active locale. */
export function locationHistoryOptions(): LocationHistoryOption[] {
    return LOCATION_HISTORY_STEPS.map(({seconds, key}) => ({seconds, label: t(key)}));
}

/**
 * Describes an arbitrary retention value — not just the {@link LOCATION_HISTORY_STEPS},
 * since a share may have been created with any window.
 */
export function locationHistoryLabel(seconds: number): string {
    const known = LOCATION_HISTORY_STEPS.find((step) => step.seconds === seconds);
    if (known) return t(known.key);
    if (seconds < 0) return t("history.preset.full");

    const hours = Math.round(seconds / 3600);
    if (hours >= 24) return t("history.custom.days", {values: {count: Math.round(hours / 24)}});
    if (hours >= 1) return t("history.custom.hours", {values: {count: hours}});
    return t("history.custom.minutes", {values: {count: Math.max(1, Math.round(seconds / 60))}});
}

/**
 * The pickable options for a share, with its current value folded in: a share
 * created with a window outside the standard steps keeps that value selectable
 * instead of silently jumping to the nearest step.
 */
export function locationHistoryOptionsFor(seconds: number): LocationHistoryOption[] {
    const steps = locationHistoryOptions();
    if (steps.some((option) => option.seconds === seconds)) return steps;

    // Unbounded is already covered by the negative check in the label helper, so
    // any custom value is a positive window and sorts between the steps.
    const custom = {seconds, label: locationHistoryLabel(seconds)};
    return [...steps, custom].sort((a, b) => {
        if (a.seconds < 0) return 1;
        if (b.seconds < 0) return -1;
        return a.seconds - b.seconds;
    });
}
