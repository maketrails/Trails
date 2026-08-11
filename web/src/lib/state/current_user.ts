import {writable} from "svelte/store";
import {getMe} from "$lib/api/auth/get_me";
import {clearAllCachedHistories} from "$lib/api/history/history_cache";

export interface User {
    id: string;
    username: string;
    /** Host of the server this account lives on, e.g. `trails.example.com`. */
    homeserver: string;
}

export const currentUser = writable<User | null>(null);

/**
 * Whether the initial auth check (getMe) has completed. Until it flips true,
 * `currentUser` being null is indistinguishable from "not signed in", so the UI
 * should show a loading state rather than the logged-out view.
 */
export const authInitialized = writable(false);

/** Drops the server-side session and redirects back into the app. */
const LOGOUT_URL = "/api/v1/webapp/auth/logout";

/**
 * Signs the user out: everything this browser holds about the session is forgotten
 * first, then the server-side session is dropped.
 *
 * The one entry point for signing out — a UI element only calls this, never the
 * endpoint directly, so anything that has to be cleaned up in the future is added
 * here and takes effect everywhere at once.
 */
export function logout(): void {
    forgetSession();
    // A navigation, not a fetch: the endpoint answers with a redirect and clears the
    // session cookie on the way. Nothing runs after this.
    location.assign(LOGOUT_URL);
}

/**
 * Forgets everything about the session that lives in this browser.
 *
 * A location history is the most personal thing this app holds, so it must not be
 * left behind on a possibly shared computer once the session has ended.
 *
 * Called by [logout], and by [updateUser] when the server reports that the session
 * is gone — that second path covers a session that expired or was signed out in
 * another tab, and must not navigate anywhere.
 */
function forgetSession(): void {
    currentUser.set(null);
    clearAllCachedHistories();
}

export async function updateUser() {
    try {
        const currentUserResult = await getMe();

        /*
         * Reaching this with no user means the server *answered* that nobody is
         * signed in — a request that failed or was blocked rejects instead and
         * leaves the store alone. That makes it the one safe point to clean up after
         * a session that ended without the user pressing "log out" here.
         */
        if (currentUserResult == null) forgetSession();
        else currentUser.set(currentUserResult);
    } finally {
        authInitialized.set(true);
    }
}
