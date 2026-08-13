import type {HistoryPoint} from "$lib/api/history/history_repository";

let points = $state<HistoryPoint[]>([]);
let key = $state<string | null>(null);

/**
 * The detail view the trail currently belongs to. Switching between two detail
 * views has both of them alive at the same time — the layout keeps the page being
 * left around for its slide-out while the page being opened is already mounted —
 * so the outgoing view's teardown would otherwise wipe the line the incoming one
 * just drew. The newest claim wins; everything an older view still publishes is
 * ignored.
 */
let owner = 0;
let claims = 0;

/** A single detail view's hold on the trail, see {@link claimMapTrail}. */
export interface MapTrailClaim {
    /**
     * Publishes the location history that should be drawn as a line on the map.
     * Pass `null` (or an empty list) to clear it again.
     *
     * [trailKey] identifies the *track*, not its current contents: which device or
     * share, and which of its two series. A view publishes the same key several times
     * while a history arrives in pieces (what the cache held, then what the server
     * added), and the map uses the key to tell "a different track" from "more of the
     * same one" — so the grow-in animation plays once per track instead of restarting
     * on every update.
     */
    set(next: HistoryPoint[] | null, trailKey: string | null): void;
    /** Takes the trail off the map again. */
    release(): void;
}

/**
 * Takes over the trail for one view — only one is shown at a time. Call this once
 * while the view is being created, not from an $effect: the claim is what marks
 * this view as the newer one, and it has to be taken before the view being left
 * tears down.
 */
export function claimMapTrail(): MapTrailClaim {
    const claim = ++claims;
    owner = claim;

    return {
        set(next: HistoryPoint[] | null, trailKey: string | null) {
            if (owner !== claim) return;
            points = next ?? [];
            key = trailKey;
        },
        release() {
            if (owner !== claim) return;
            points = [];
            key = null;
        },
    };
}

/** The trail currently shown on the map (reactive), oldest point first. */
export const mapTrail = {
    get points() {
        return points;
    },
    /** Which track [points] belong to; `null` when there is no trail. */
    get key() {
        return key;
    },
};
