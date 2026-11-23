import { browser } from "$app/env";
import { locale, waitLocale } from "svelte-i18n";
import { localeFromNavigator } from "#lib/i18n/index.ts";
import type { LayoutLoad } from "./$types";

// Imported for their side effects: registering the catalogues and calling `init`
// (i18n), and keeping dayjs' process-global locale in lockstep with the app
// locale. Both must have happened before the first message or date is formatted.
import "#lib/i18n/index.ts";
import "#lib/dayjs.ts";

/**
 * Settles the active locale before anything renders. In the browser that is the
 * user's own browser setting; while rendering on the server the language was
 * already negotiated from `Accept-Language` in `src/hooks.server.ts`, so this
 * leaves it alone. Awaiting `waitLocale` guarantees the catalogue is in place, so
 * the server-rendered HTML never ships raw message keys.
 */
export const load: LayoutLoad = async () => {
    if (browser) locale.set(localeFromNavigator());
    await waitLocale();
};
