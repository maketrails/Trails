import requireResponseIsFromTrails from "#lib/api/requireResponseIsFromTrails.ts";
import {shareOriginBase} from "#lib/api/shares/get_share_snapshot.ts";

/** Why a rename was refused, or `"ok"` if it went through. */
export type RenameShareResult = "ok" | "name-taken" | "name-blank" | "error";

/**
 * Share-related server calls. Keeping them here means UI components never touch
 * `fetch` directly — they depend on this repository instead.
 */
export const ShareRepository = {
    /**
     * Returns a saved share. This is client-driven federation: the redemption
     * ({@link https ActiveShare}) is deleted on its origin homeserver, and the
     * saved reference is removed from the current account. Deleting the
     * redemption deliberately does not lift the share's lock.
     *
     * `homeserver` is the share's origin (empty for a same-server share). The
     * origin call is best-effort (it may be a foreign host); success is decided
     * by the account-side removal, which is what drops the share from the list.
     * Resolves `true` on success, `false` on failure.
     */
    async returnShare(shareId: string, homeserver: string): Promise<boolean> {
        // Delete the redemption on its origin homeserver. Capability-based, so no
        // auth; a plain POST stays a CORS "simple request" for foreign hosts.
        const base = shareOriginBase(homeserver);
        try {
            await fetch(`${base}/api/v1/active-shares/${shareId}/return`, {method: "POST"});
        } catch {
            // Best-effort — still remove the account reference below.
        }

        // Remove the saved reference from this account (same origin, authenticated).
        let response: Response;
        try {
            response = await fetch(
                `/api/v1/me/shares/${shareId}?homeserver=${encodeURIComponent(homeserver)}`,
                {method: "DELETE"},
            );
        } catch {
            return false;
        }
        requireResponseIsFromTrails(response);
        return response.ok;
    },

    /**
     * Changes the settings of a share the current user emitted. Only the given
     * fields are touched; the change applies to everyone who already redeemed the
     * share. Resolves `true` on success, `false` on any failure (network error,
     * forbidden, …).
     *
     * `location_history_seconds` uses the share's own encoding: `0` shares no
     * history, a negative value an unbounded window (see
     * {@link INFINITE_HISTORY_SECONDS}).
     */
    async updateEmittedShare(
        shareId: string,
        settings: {location_history_seconds?: number; share_battery_state?: boolean},
    ): Promise<boolean> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/share/${shareId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(settings),
            });
        } catch {
            return false;
        }
        requireResponseIsFromTrails(response);
        return response.ok;
    },

    /**
     * Renames a share the current user emitted. A share name has to be non-blank
     * and unique among the user's shares, so a rejected name is reported as its
     * own result — the dialog shows it on the input rather than as a failure.
     */
    async renameEmittedShare(shareId: string, name: string): Promise<RenameShareResult> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/share/${shareId}`, {
                method: "PATCH",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({share_name: name}),
            });
        } catch {
            return "error";
        }
        requireResponseIsFromTrails(response);

        // `UpdateShareResponse` on the wire: the sealed case names the outcome, so
        // the status code is only the fallback when there is no (readable) body.
        const body = await response.json().catch(() => null) as {type?: string} | null;
        switch (body?.type) {
            case "share-updated":
                return "ok";
            case "share-name-already-exists":
                return "name-taken";
            case "share-name-empty":
                return "name-blank";
        }
        return response.ok ? "ok" : "error";
    },

    /**
     * Deletes a share the current user emitted. Every link handed out stops
     * working: the share's redemptions go with it, so nobody can see the device's
     * location through it anymore. Resolves `true` on success, `false` on any
     * failure (network error, forbidden, …).
     */
    async removeEmittedShare(shareId: string): Promise<boolean> {
        let response: Response;
        try {
            response = await fetch(`/api/v1/share/${shareId}`, {method: "DELETE"});
        } catch {
            return false;
        }
        requireResponseIsFromTrails(response);
        return response.ok;
    },
};
