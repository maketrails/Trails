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
    points: HistoryPoint[];
}

/**
 * A reported bearing accuracy of exactly 0 means the device had no real fix on its
 * direction of travel, and such points sit off the actual route often enough to
 * bend the drawn trail. They are dropped here, at the boundary, so every consumer
 * (trail, camera, detail views) works off the same cleaned list. A missing accuracy
 * (`null`) is unknown, not zero, and stays.
 */
function isUsable(point: HistoryPoint): boolean {
    return point.bearing_accuracy !== 0;
}

async function readHistory(response: Response): Promise<LocationHistory | null> {
    if (!response.ok) return null;
    try {
        const history = await response.json() as LocationHistory;
        return {...history, points: history.points.filter(isUsable)};
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
export const HistoryRepository = {
    /**
     * The complete history of one of the current user's own devices. Owners are
     * never limited, so `history_seconds` is always null here. Resolves `null` on
     * any failure (network error, unknown device, someone else's device).
     */
    async forDevice(deviceId: string, source: HistorySource = "optimized"): Promise<LocationHistory | null> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/devices/${deviceId}/history?source=${source}`);
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
     */
    async forShare(shareId: string, homeserver: string): Promise<LocationHistory | null> {
        const base = shareOriginBase(homeserver);
        let response: Response;
        try {
            response = await fetch(`${base}/api/v1/active-shares/${shareId}/history`);
        } catch {
            return null;
        }
        return readHistory(response);
    },
};
