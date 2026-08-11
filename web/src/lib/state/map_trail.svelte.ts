import type {HistoryPoint} from "$lib/api/history/history_repository";

let points = $state<HistoryPoint[]>([]);
let key = $state<string | null>(null);

/**
 * Publishes the location history that should be drawn as a line on the map.
 * Pass `null` (or an empty list) to clear it again — the detail views do that on
 * leave, so the trail lives exactly as long as the view that loaded it.
 *
 * Only one trail is shown at a time; there is only ever one open detail view.
 *
 * [trailKey] identifies the *track*, not its current contents: which device or share,
 * and which of its two series. A view publishes the same key several times while a
 * history arrives in pieces (what the cache held, then what the server added), and the
 * map uses the key to tell "a different track" from "more of the same one" — so the
 * grow-in animation plays once per track instead of restarting on every update.
 */
export function setMapTrail(next: HistoryPoint[] | null, trailKey: string | null) {
    points = next ?? [];
    key = trailKey;
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
