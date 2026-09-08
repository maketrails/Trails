import type {Snippet} from "svelte";

let content = $state<Snippet | null>(null);

/**
 * The view the strip currently belongs to. Two detail views are alive at the same
 * time while a navigation slides — the layout keeps the page being left around for
 * its slide-out — so without an owner the outgoing view's teardown would wipe what
 * the incoming one just published. The newest claim wins.
 */
let owner = 0;
let claims = 0;

/** A single view's hold on the strip, see {@link claimMapOverlay}. */
export interface MapOverlayClaim {
    /** Publishes what the strip draws. Pass `null` to leave it empty. */
    set(next: Snippet | null): void;
    /** Takes the strip's contents off the map again. */
    release(): void;
}

/**
 * Takes over the strip below the map for one view — only one is shown at a time.
 * Call this once while the view is being created, not from an $effect: the claim
 * is what marks this view as the newer one, and it has to be taken before the view
 * being left tears down.
 */
export function claimMapOverlay(): MapOverlayClaim {
    const claim = ++claims;
    owner = claim;

    return {
        set(next: Snippet | null) {
            if (owner !== claim) return;
            content = next;
        },
        release() {
            if (owner !== claim) return;
            content = null;
        },
    };
}

/** What the strip between card and camera switch draws (reactive). */
export const mapOverlay = {
    get content() {
        return content;
    },
};
