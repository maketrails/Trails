import type { Handle } from "@sveltejs/kit/hooks";
import { locale } from "svelte-i18n";
import { localeFromAcceptLanguage } from "#lib/i18n/index.ts";

/**
 * Negotiates the response language from `Accept-Language` and applies it before
 * the page is rendered, so the server-rendered markup and `<html lang>` are
 * already in the visitor's language. The browser takes over from
 * `src/routes/+layout.ts`, where `navigator` is available.
 *
 * svelte-i18n's locale is a module-level store, hence a plain assignment per
 * request rather than something carried on the event.
 */
export const handle: Handle = async ({event, resolve}) => {
    const negotiated = localeFromAcceptLanguage(event.request.headers.get("accept-language"));
    event.locals.locale = negotiated;
    locale.set(negotiated);

    return resolve(event, {
        transformPageChunk: ({html}) => html.replace("%lang%", negotiated),
    });
};
