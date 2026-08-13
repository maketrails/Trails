import {cubicOut} from "svelte/easing";
import type {TransitionConfig} from "svelte/transition";

const PIN_TRANSITION_MS = 220;

/**
 * How a pin or a bundle joins and leaves the map: it grows out of the coordinate
 * it marks and shrinks back into it.
 *
 * This is what makes bundling readable. Pins that come close enough are replaced
 * by the pill drawn in their place, and the pill by the single pins again as they
 * separate — at the same spot, in the same instant, so without the two ends
 * meeting in one movement the swap would just read as a flicker.
 *
 * The node needs its transform origin set to its anchor (the tip), or it grows out
 * of its middle and the marker appears to drift.
 */
export function pinPop(_node: Element): TransitionConfig {
    const reducedMotion =
        typeof window !== "undefined" &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    return {
        duration: reducedMotion ? 0 : PIN_TRANSITION_MS,
        easing: cubicOut,
        css: (t: number) => `transform: scale(${0.55 + 0.45 * t}); opacity: ${t};`,
    };
}
