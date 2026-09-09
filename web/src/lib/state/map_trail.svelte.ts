import type {HistoryPoint} from "$lib/api/history/history_repository";

/** A stretch of time, as the two moments that bound it. */
export interface TrailRange {
    start: Date;
    end: Date;
}

// Raw, not deep-reactive: a history runs to hundreds of thousands of points, and a
// reactive proxy over it makes every read of every point go through a trap — walking
// one costs about eighteen times what walking a plain array does. The list is only
// ever replaced, never edited in place, so nothing here needs the proxy.
let points = $state.raw<HistoryPoint[]>([]);
let key = $state<string | null>(null);

// What the trail is being read through: the stretch a timeline shows, and the range
// marked inside it. Neither changes what the line contains, only how it is coloured.
let window = $state<TrailRange | null>(null);
let selection = $state<TrailRange | null>(null);

/**
 * The moment a reader is pointing at on the timeline, or null while they are not.
 * The map answers it by putting its puck at that spot on the line — which is the
 * whole point of a timeline standing next to a map.
 */
let hoveredAt = $state<number | null>(null);

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
    /**
     * Publishes what the trail is being read through: [window] is the stretch on
     * show, [selection] the range marked inside it. Pass `null` for either to say
     * there is none — with no window at all the whole line reads as being on show.
     */
    focus(window: TrailRange | null, selection: TrailRange | null): void;
    /**
     * Points at a moment, or at nothing. Unlike the window and the range this is not
     * a state anyone acts on, only one they look at, so it is published on its own and
     * cleared as soon as the pointer leaves.
     */
    hover(at: Date | null): void;
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
        focus(nextWindow, nextSelection) {
            if (owner !== claim) return;
            window = nextWindow;
            selection = nextSelection;
        },
        hover(at: Date | null) {
            if (owner !== claim) return;
            hoveredAt = at?.getTime() ?? null;
        },
        release() {
            if (owner !== claim) return;
            hoveredAt = null;
            points = [];
            key = null;
            window = null;
            selection = null;
        },
    };
}

/** The trail currently shown on the map (reactive), oldest point first. */
export const mapTrail = {
    get points() {
        return points;
    },
    /** The moment being pointed at on the timeline, or null. */
    get hoveredAt() {
        return hoveredAt;
    },
    /** Which track [points] belong to; `null` when there is no trail. */
    get key() {
        return key;
    },
    /** The stretch on show, or `null` while the whole trail counts as on show. */
    get window() {
        return window;
    },
    /** The marked range, or `null` while nothing is marked. */
    get selection() {
        return selection;
    },
};
