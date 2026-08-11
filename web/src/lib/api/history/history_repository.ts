import requireResponseIsFromTrails from "$lib/api/requireResponseIsFromTrails";
import {shareOriginBase} from "$lib/api/shares/get_share_snapshot";
import type {Battery} from "$lib/state/webapp_socket.svelte";

/**
 * One recorded position of a device. `timestamp` is epoch **milliseconds**,
 * matching `LastLocation.found_at`.
 *
 * `battery` is only present when the caller may see the battery state (always for
 * the user's own devices, for a share only when it opted in) and the device
 * actually reported it.
 *
 * `is_raw` marks a measurement the optimizer has not reached yet. The trail draws
 * those stretches apart from the optimized ones rather than presenting the whole
 * line as equally clean.
 */
export interface HistoryPoint {
    /**
     * The stored row's own id, for pointing at exactly this position when inspecting the
     * data. Stable for a measurement, but an optimized position gets a new one on every
     * rebuild — `timestamp` is the identity to rely on.
     */
    id: string;
    timestamp: number;
    latitude: number;
    longitude: number;
    location_accuracy: number;
    bearing: number;
    bearing_accuracy: number | null;
    battery: Battery | null;
    is_raw: boolean;
}

/**
 * Which of a device's two series to read: the optimized track (optimized
 * positions as far as they reach, then the raw tail behind them), or only the
 * measurements as the device reported them.
 */
export type HistorySource = "optimized" | "raw";

/** A device's recorded location history, oldest point first. */
export interface LocationHistory {
    /**
     * The retention window the server applied, in seconds. `null` means nothing
     * was cut off — the caller's own device, or a share with an unbounded window.
     */
    history_seconds: number | null;
    /**
     * Where to continue reading: hand this back as `since` and the next answer holds
     * what has been *stored* in the meantime. `null` when nothing came back, in which
     * case the cursor already held stays valid.
     */
    cursor: number | null;
    points: HistoryPoint[];
}

/**
 * Hands the history through as the server sent it. Cleaning up positions is the
 * optimizer's job now — dropping some of them here as well would mean the
 * raw view shows fewer positions than the statistics count, and the optimized
 * track would get thinned out a second time on the way to the map.
 */
async function readHistory(response: Response): Promise<LocationHistory | null> {
    if (!response.ok) return null;
    try {
        return await response.json() as LocationHistory;
    } catch {
        return null;
    }
}

/**
 * Location-history calls. Keeping them here means UI components never touch
 * `fetch` directly — they depend on this repository instead.
 *
 * History is a one-shot read, deliberately not part of the snapshot sockets:
 * it is fetched when a detail view opens and does not update live.
 */
/**
 * `?since=<epoch millis>` is the `cursor` of an earlier answer: the server then reads
 * only what has been **stored** since, which is what catches the optimizer's rebuilt
 * positions as well — they carry old recording timestamps but a new storage time. The
 * bound is inclusive, so the answer overlaps what the caller already has.
 */
function historyQuery(since?: number, source?: HistorySource): string {
    const query = new URLSearchParams();
    if (source != null) query.set("source", source);
    if (since != null) query.set("since", String(since));
    const rendered = query.toString();
    return rendered === "" ? "" : `?${rendered}`;
}

export const HistoryRepository = {
    /**
     * The history of one of the current user's own devices. Owners are never
     * limited, so `history_seconds` is always null here. Resolves `null` on any
     * failure (network error, unknown device, someone else's device).
     *
     * [since] continues from the `cursor` of an earlier answer instead of reading the
     * whole history — see [historyQuery].
     */
    async forDevice(
        deviceId: string,
        source: HistorySource = "optimized",
        since?: number,
    ): Promise<LocationHistory | null> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/devices/${deviceId}/history${historyQuery(since, source)}`);
        } catch {
            return null;
        }
        requireResponseIsFromTrails(response);
        return readHistory(response);
    },

    /**
     * The history a redeemed share is allowed to see, fetched straight from its
     * origin homeserver (`homeserver` is empty for a same-server share). The
     * active-share id is the capability, so no auth is sent — and because the
     * response may be cross-origin, the `X-Trails-Origin` marker is not checked
     * (custom headers aren't readable cross-origin), same as the share snapshot.
     *
     * How far back the server goes is the share's decision, reported back as
     * `history_seconds`. Resolves `null` on any failure (unknown/returned share,
     * network or CORS error).
     *
     * [since] can only narrow that window further, never widen it — see the
     * endpoint's documentation.
     */
    async forShare(shareId: string, homeserver: string, since?: number): Promise<LocationHistory | null> {
        const base = shareOriginBase(homeserver);
        let response: Response;
        try {
            response = await fetch(`${base}/api/v1/active-shares/${shareId}/history${historyQuery(since)}`);
        } catch {
            return null;
        }
        return readHistory(response);
    },
};
