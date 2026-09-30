import type {MovementItem} from "$lib/api/history/history_repository";

/**
 * The ways of moving this web app knows, and what they are drawn in — on the timeline
 * and on the map alike, so a reader can match the two at a glance.
 *
 * Hex rather than Tailwind classes or theme tokens: mapbox-gl paints the trail and can
 * only parse plain colours. They are Tailwind's sky-600, orange-500 and emerald-600,
 * which hold up on the light and the dark basemap.
 */
export const MOVEMENT_COLORS = {
    walking: "#0284c7",
    cycling: "#f97316",
    travel: "#059669",
} as const;

/** A movement type the web app knows how to show. */
export type MovementType = keyof typeof MOVEMENT_COLORS;

/**
 * What [movement] is shown as, or `null` for a type the server added after this build —
 * shown as unknown rather than guessed at.
 */
export function movementTypeOf(movement: MovementItem): MovementType | null {
    return movement.type in MOVEMENT_COLORS ? (movement.type as MovementType) : null;
}

/**
 * The movement [time] falls into, or `null` between movements. [movements] is oldest
 * first and free of overlaps, so a binary search finds it.
 */
export function movementAt(movements: MovementItem[], time: number): MovementItem | null {
    let low = 0;
    let high = movements.length - 1;
    while (low <= high) {
        const middle = (low + high) >> 1;
        const movement = movements[middle];
        if (time < movement.from) high = middle - 1;
        else if (time > movement.to) low = middle + 1;
        else return movement;
    }
    return null;
}

/** [meters] the way it reads at a glance: kilometres from one kilometre on, metres below. */
export function formatDistance(meters: number, locale: string | null | undefined): string {
    return meters < 1_000
        ? new Intl.NumberFormat(locale ?? undefined, {style: "unit", unit: "meter", maximumFractionDigits: 0}).format(meters)
        : new Intl.NumberFormat(locale ?? undefined, {style: "unit", unit: "kilometer", maximumFractionDigits: 1}).format(meters / 1_000);
}
