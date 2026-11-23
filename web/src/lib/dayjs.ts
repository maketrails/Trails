import dayjs from "dayjs";
import localizedFormat from "dayjs/plugin/localizedFormat";
import relativeTime from "dayjs/plugin/relativeTime";
import "dayjs/locale/de";
import {locale as activeLocale} from "svelte-i18n";
// Guarantees `init` ran (and with it the fallback locale) before the first
// subscription below fires.
import {FALLBACK_LOCALE, SUPPORTED_LOCALES} from "#lib/i18n/index.ts";

dayjs.extend(relativeTime);
// `localizedFormat` adds the L/LT/LLL tokens, so no view has to spell out a
// language-specific pattern like `DD.MM.YYYY`.
dayjs.extend(localizedFormat);

// dayjs ships English as its built-in locale, so only German needs importing
// above. Its locale is global rather than per-instance, which is why it is kept
// in lockstep with the app locale here instead of being passed at every call
// site. The app locale is auto-detected once per page load and never switched, so
// this settles before anything formats a date.
activeLocale.subscribe((tag) => {
    const language = (tag ?? "").toLowerCase().split("-")[0];
    dayjs.locale(SUPPORTED_LOCALES.includes(language) ? language : FALLBACK_LOCALE);
});

export default dayjs;
