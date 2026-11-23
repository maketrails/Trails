import type {Battery, LastLocation} from "#lib/state/webapp_socket.svelte.ts";

/** A shared device's current state, fetched directly from its origin homeserver. */
export interface ShareSnapshot {
    name: string;
    manufacturer: string;
    model: string;
    device_friendly_name: string;
    owner_username: string;
    last_location: LastLocation | null;
    battery: Battery | null;
}

/**
 * Resolves a homeserver identifier into a URL base. An empty value means the
 * current origin (relative URLs); otherwise it is treated as an origin, with
 * https:// assumed when no scheme is given.
 */
export function shareOriginBase(homeserver: string): string {
    if (!homeserver) return "";
    const withScheme = /^https?:\/\//i.test(homeserver) ? homeserver : `https://${homeserver}`;
    return withScheme.replace(/\/$/, "");
}

/**
 * Fetches a shared device's snapshot straight from its origin homeserver. The
 * active-share id is the capability, so no auth is sent. Returns null on any
 * failure (unknown share, network/CORS error).
 */
export async function getShareSnapshot(homeserver: string, shareId: string): Promise<ShareSnapshot | null> {
    const base = shareOriginBase(homeserver);
    let response: Response;
    try {
        response = await fetch(`${base}/api/v1/active-shares/${shareId}/snapshot`);
    } catch {
        return null;
    }
    if (!response.ok) return null;
    try {
        return await response.json() as ShareSnapshot;
    } catch {
        return null;
    }
}
