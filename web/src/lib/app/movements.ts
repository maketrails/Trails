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
