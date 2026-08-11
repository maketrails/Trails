import {
    HistoryRepository,
    type HistoryPoint,
    type HistorySource,
    type LocationHistory,
} from "$lib/api/history/history_repository";

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

function fetchFor(target: HistoryTarget): Promise<LocationHistory | null> {
    return target.kind === "device"
        ? HistoryRepository.forDevice(target.deviceId, target.source ?? "optimized")
        : HistoryRepository.forShare(target.shareId, target.homeserver);
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

        loading = true;
        // Guards against a stale response overwriting a newer target's history:
        // the teardown below runs before the effect re-runs.
        let cancelled = false;

        fetchFor(current)
            // A rejection (e.g. a response that isn't from Trails) must surface as
            // a failed load rather than leaving `loading` stuck true forever.
            .catch(() => null)
            .then((history) => {
                if (cancelled) return;
                loading = false;
                if (history == null) {
                    failed = true;
                    return;
                }
                points = history.points;
                historySeconds = history.history_seconds;
            });

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
