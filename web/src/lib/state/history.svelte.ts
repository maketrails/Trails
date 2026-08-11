import {
    HistoryRepository,
    type HistoryPoint,
    type HistorySource,
    type LocationHistory,
} from "$lib/api/history/history_repository";
import {
    applyFreshPoints,
    clearCachedHistory,
    readCachedHistory,
    storeCachedHistory,
} from "$lib/api/history/history_cache";

/**
 * What to load a history for — one of the user's own devices, or a share
 * (possibly living on a foreign homeserver).
 */
export type HistoryTarget =
    | {kind: "device"; deviceId: string; source?: HistorySource}
    | {kind: "share"; shareId: string; homeserver: string};

/** A history load in progress or finished. `null` history + `failed` = gave up. */
export interface HistoryLoad {
    /** Oldest point first. Empty while loading, or when the target has no history. */
    readonly points: HistoryPoint[];
    /** The retention window the server applied, in seconds; null = nothing cut off. */
    readonly historySeconds: number | null;
    readonly loading: boolean;
    /** True once a load finished without a result (unknown target, network error, …). */
    readonly failed: boolean;
}

function fetchFor(target: HistoryTarget, since?: number): Promise<LocationHistory | null> {
    return target.kind === "device"
        ? HistoryRepository.forDevice(target.deviceId, target.source ?? "optimized", since)
        : HistoryRepository.forShare(target.shareId, target.homeserver, since);
}

/** Which cached series [target] reads, or `null` when it must not be cached. */
interface CacheTarget {
    deviceId: string;
    source: HistorySource;
}

/**
 * Own devices only, but both of their series — a rebuilt optimized track is caught by
 * the storage-time cursor, so it no longer has to be re-read whole.
 *
 * A share stays uncached: its retention window moves with time, so positions drop out of
 * what it may reveal, and a cache would go on showing them.
 */
function cacheTargetFor(target: HistoryTarget): CacheTarget | null {
    return target.kind === "device"
        ? {deviceId: target.deviceId, source: target.source ?? "optimized"}
        : null;
}

/**
 * Loads the location history for whatever [target] currently resolves to, and
 * keeps it for as long as the calling component lives. Returning `null` from
 * [target] clears the result without fetching.
 *
 * Deliberately one-shot: unlike the snapshot sockets there is no live update, so
 * opening a detail view reads the history exactly once. Changing the target
 * (navigating between two devices) discards the in-flight response and reloads.
 *
 * A cacheable target (see [cacheTargetFor]) is served from the cache first, so the
 * track is on the map before the request goes out, and only what the server has
 * stored since then is read.
 *
 * Must be called during component initialisation — the internal effect is owned
 * by that component, so leaving the view drops the result.
 */
export function loadHistory(target: () => HistoryTarget | null): HistoryLoad {
    let points = $state<HistoryPoint[]>([]);
    let historySeconds = $state<number | null>(null);
    let loading = $state(false);
    let failed = $state(false);

    $effect(() => {
        const current = target();

        points = [];
        historySeconds = null;
        failed = false;

        if (current == null) {
            loading = false;
            return;
        }

        const cacheTarget = cacheTargetFor(current);

        loading = true;
        // Guards against a stale response overwriting a newer target's history:
        // the teardown below runs before the effect re-runs.
        let cancelled = false;

        void (async () => {
            // What the response is applied to; dropped as soon as the cache turns out
            // not to describe this history any more.
            let base = cacheTarget == null
                ? null
                : await readCachedHistory(cacheTarget.deviceId, cacheTarget.source);
            if (cancelled) return;

            // What the cache holds goes on the map before the request even goes out;
            // the response only has to bring what has been stored since.
            if (base != null) points = base.points;

            // A rejection (e.g. a response that isn't from Trails) must surface as
            // a failed load rather than leaving `loading` stuck true forever.
            let history = await fetchFor(current, base?.cursor ?? undefined).catch(() => null);

            /*
             * The cursor is an inclusive bound, so a server that still holds what the
             * cache was last filled from answers with at least that much. An empty
             * answer means it is gone — the history was wiped, the database restored —
             * and nothing in the cache can be trusted, so it is thrown away and read
             * in full.
             */
            if (!cancelled && base?.cursor != null && history?.points.length === 0) {
                base = null;
                points = [];
                if (cacheTarget != null) await clearCachedHistory(cacheTarget.deviceId);
                history = await fetchFor(current).catch(() => null);
            }

            if (cancelled) return;
            loading = false;

            if (history == null) {
                // A refresh that failed still leaves the cached history on screen —
                // stale positions beat an empty map.
                failed = true;
                return;
            }

            points = base == null ? history.points : applyFreshPoints(base.points, history.points);
            historySeconds = history.history_seconds;
            // Only what the answer actually carried is written back: the cache already
            // holds the rest, and rewriting a month of positions on every visit would
            // be the very cost this cache exists to avoid.
            if (cacheTarget != null) {
                void storeCachedHistory(
                    cacheTarget.deviceId,
                    cacheTarget.source,
                    history.points,
                    history.cursor,
                );
            }
        })();

        return () => {
            cancelled = true;
        };
    });

    return {
        get points() {
            return points;
        },
        get historySeconds() {
            return historySeconds;
        },
        get loading() {
            return loading;
        },
        get failed() {
            return failed;
        },
    };
}
