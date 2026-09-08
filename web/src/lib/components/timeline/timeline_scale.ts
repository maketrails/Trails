/**
 * The arithmetic behind a timeline axis: which unit a window is divided by at a
 * given zoom, where that unit's boundaries lie, and how they read.
 *
 * Knows nothing of Svelte or of the DOM — it takes a stretch of time and a width
 * in pixels and answers with times and labels — so it can be tested on its own and
 * reused by any timeline.
 */

const SECOND = 1_000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** The units an axis is divided by, from a second up to a year. */
export type Unit = "second" | "minute" | "hour" | "day" | "week" | "month" | "year";

export interface Scale {
    /** What one labelled tick stands for. */
    unit: Unit;
    /** How many of them lie between two ticks. Always divides its unit evenly. */
    every: number;
    /**
     * The coarser unit whose boundaries are drawn as separators. An axis of hours
     * is only readable while it says which day they belong to, and that is what
     * the separator and its label are for.
     */
    separator: Unit;
    /** Rough length in milliseconds. Only used to pick a scale for a zoom level. */
    length: number;
}

/**
 * The zoom ladder. Ticks are placed on real calendar boundaries rather than on
 * multiples of a duration, so a day starts at local midnight, a month on its
 * first, and neither daylight saving nor a 31-day month shifts anything.
 */
export const SCALES: readonly Scale[] = [
    {unit: "second", every: 1, separator: "minute", length: SECOND},
    {unit: "second", every: 5, separator: "minute", length: 5 * SECOND},
    {unit: "second", every: 15, separator: "minute", length: 15 * SECOND},
    {unit: "second", every: 30, separator: "minute", length: 30 * SECOND},
    {unit: "minute", every: 1, separator: "hour", length: MINUTE},
    {unit: "minute", every: 5, separator: "hour", length: 5 * MINUTE},
    {unit: "minute", every: 15, separator: "hour", length: 15 * MINUTE},
    {unit: "minute", every: 30, separator: "hour", length: 30 * MINUTE},
    {unit: "hour", every: 1, separator: "day", length: HOUR},
    {unit: "hour", every: 3, separator: "day", length: 3 * HOUR},
    {unit: "hour", every: 6, separator: "day", length: 6 * HOUR},
    {unit: "hour", every: 12, separator: "day", length: 12 * HOUR},
    {unit: "day", every: 1, separator: "week", length: DAY},
    {unit: "week", every: 1, separator: "month", length: 7 * DAY},
    {unit: "month", every: 1, separator: "year", length: 30 * DAY},
    {unit: "month", every: 3, separator: "year", length: 90 * DAY},
    {unit: "year", every: 1, separator: "year", length: 365 * DAY},
];

/** How a tick names its own unit: as short as it can be at that zoom level. */
const TICK_FORMATS: Record<Unit, Intl.DateTimeFormatOptions> = {
    second: {hour: "2-digit", minute: "2-digit", second: "2-digit"},
    minute: {hour: "2-digit", minute: "2-digit"},
    hour: {hour: "2-digit", minute: "2-digit"},
    day: {weekday: "short", day: "numeric"},
    week: {day: "numeric", month: "short"},
    month: {month: "short"},
    year: {year: "numeric"},
};

/** How a separator names its section — the context the ticks next to it lack. */
const SEPARATOR_FORMATS: Record<Unit, Intl.DateTimeFormatOptions> = {
    second: {hour: "2-digit", minute: "2-digit", second: "2-digit"},
    minute: {hour: "2-digit", minute: "2-digit"},
    hour: {hour: "2-digit", minute: "2-digit"},
    day: {weekday: "short", day: "numeric", month: "short"},
    week: {day: "numeric", month: "short"},
    month: {month: "long", year: "numeric"},
    year: {year: "numeric"},
};

/**
 * Roughly how much room one labelled tick wants, in pixels. Tight on purpose: the
 * labels are short, and a wider setting makes whole steps of the ladder
 * unreachable — a week-long window would be labelled once, with its week.
 */
export const LABEL_SPACING = 80;

/** Unlabelled ticks closer together than this are dropped. */
export const MINOR_SPACING = 20;

/** How far the first separator has to stand from the left edge to leave room for a leading label. */
export const CONTEXT_SPACING = 60;

/** Stepping through a window can never take more rounds than this. */
const MAX_BOUNDARIES = 500;

/** The local calendar of one locale, which is all the axis needs to place its marks. */
export interface Calendar {
    /** Which weekday a week starts on, Sunday being 0. */
    weekStart: number;
    /** The start of the [unit] × [every] section that contains [time]. */
    floorTo(time: number, unit: Unit, every: number): Date;
    /** The next section after [date]. */
    advance(date: Date, unit: Unit, every: number): Date;
    /** Every [unit] × [every] boundary in [start]…[end], oldest first. */
    boundaries(start: number, end: number, unit: Unit, every: number): number[];
}

/**
 * Which weekday a week starts on for [locale] — Monday in most of the world,
 * Sunday in the US — so a week boundary lands where the reader expects it.
 */
export function weekStartOf(locale?: string | null): number {
    try {
        // Intl counts Monday as 1 and Sunday as 7; getDay() counts Sunday as 0.
        const info = new Intl.Locale(locale ?? "en") as Intl.Locale & {getWeekInfo?: () => {firstDay: number}};
        return (info.getWeekInfo?.().firstDay ?? 1) % 7;
    } catch {
        return 1;
    }
}

/** The calendar of [locale], read on the machine's own clock. */
export function calendarFor(locale?: string | null): Calendar {
    const weekStart = weekStartOf(locale);

    const floorTo = (time: number, unit: Unit, every: number): Date => {
        const date = new Date(time);
        date.setMilliseconds(0);
        if (unit === "second") {
            date.setSeconds(Math.floor(date.getSeconds() / every) * every);
            return date;
        }

        date.setSeconds(0);
        if (unit === "minute") {
            date.setMinutes(Math.floor(date.getMinutes() / every) * every);
            return date;
        }

        date.setMinutes(0);
        if (unit === "hour") {
            date.setHours(Math.floor(date.getHours() / every) * every);
            return date;
        }

        date.setHours(0);
        if (unit === "day") return date;
        if (unit === "week") {
            date.setDate(date.getDate() - ((date.getDay() - weekStart + 7) % 7));
            return date;
        }

        date.setDate(1);
        if (unit === "month") {
            date.setMonth(Math.floor(date.getMonth() / every) * every);
            return date;
        }

        date.setMonth(0);
        return date;
    };

    // Going through the setters rather than adding milliseconds is what keeps the
    // marks on the calendar: they carry a 31-day month and a daylight saving change.
    const advance = (date: Date, unit: Unit, every: number): Date => {
        const next = new Date(date);
        if (unit === "second") next.setSeconds(next.getSeconds() + every);
        else if (unit === "minute") next.setMinutes(next.getMinutes() + every);
        else if (unit === "hour") next.setHours(next.getHours() + every);
        else if (unit === "day") next.setDate(next.getDate() + every);
        else if (unit === "week") next.setDate(next.getDate() + 7 * every);
        else if (unit === "month") next.setMonth(next.getMonth() + every);
        else next.setFullYear(next.getFullYear() + every);
        return next;
    };

    return {
        weekStart,
        floorTo,
        advance,
        boundaries(start, end, unit, every) {
            const result: number[] = [];
            let cursor = floorTo(start, unit, every);

            // The guard is what keeps a scale that somehow doesn't fit from locking up
            // the tab; at one mark per MINOR_SPACING pixels it is never reached.
            for (let guard = 0; guard < MAX_BOUNDARIES && cursor.getTime() <= end; guard++) {
                if (cursor.getTime() >= start) result.push(cursor.getTime());
                cursor = advance(cursor, unit, every);
            }
            return result;
        },
    };
}

export interface AxisTick {
    time: number;
    label: string;
}

export interface AxisSeparator {
    time: number;
    label: string;
    /**
     * True for the section the window opens inside of: it began before the window
     * did, so it has no line to draw — only a label, which is the one the reader
     * needs most.
     */
    leading: boolean;
}

export interface Axis {
    /** The scale the marks were placed on, so a caller can tell what it is looking at. */
    scale: Scale;
    /** Labelled ticks. */
    ticks: AxisTick[];
    /** One step finer and unlabelled, or empty while that would be too dense. */
    minorTicks: number[];
    /** The lines that say which day, month or year the ticks between them belong to. */
    separators: AxisSeparator[];
}

export interface AxisOptions {
    /** The window, as epoch milliseconds. */
    start: number;
    end: number;
    /** How wide the window is drawn, in pixels. Zero yields an empty axis. */
    width: number;
    locale?: string | null;
    labelSpacing?: number;
    minorSpacing?: number;
    contextSpacing?: number;
}

/** Everything to draw on the axis of one window. Positions are left to the caller. */
export function axisFor(options: AxisOptions): Axis {
    const {
        start,
        end,
        width,
        locale,
        labelSpacing = LABEL_SPACING,
        minorSpacing = MINOR_SPACING,
        contextSpacing = CONTEXT_SPACING,
    } = options;

    const msPerPixel = width > 0 ? (end - start) / width : 0;
    const scale = SCALES.find((candidate) => candidate.length >= msPerPixel * labelSpacing) ?? SCALES[SCALES.length - 1];
    if (msPerPixel <= 0) return {scale, ticks: [], minorTicks: [], separators: []};

    const calendar = calendarFor(locale);
    const tickFormat = new Intl.DateTimeFormat(locale ?? undefined, TICK_FORMATS[scale.unit]);
    const separatorFormat = new Intl.DateTimeFormat(locale ?? undefined, SEPARATOR_FORMATS[scale.separator]);

    const ticks = calendar
        .boundaries(start, end, scale.unit, scale.every)
        .map((time) => ({time, label: tickFormat.format(time)}));

    const finer = SCALES[SCALES.indexOf(scale) - 1];
    const major = new Set(ticks.map((tick) => tick.time));
    const minorTicks = finer != null && finer.length / msPerPixel >= minorSpacing
        ? calendar.boundaries(start, end, finer.unit, finer.every).filter((time) => !major.has(time))
        : [];

    if (scale.separator === scale.unit) return {scale, ticks, minorTicks, separators: []};

    const separators: AxisSeparator[] = calendar
        .boundaries(start, end, scale.separator, 1)
        .map((time) => ({time, label: separatorFormat.format(time), leading: false}));

    const leading = calendar.floorTo(start, scale.separator, 1).getTime();
    const firstGap = separators.length > 0 ? (separators[0].time - start) / msPerPixel : Infinity;
    if (leading < start && firstGap >= contextSpacing) {
        separators.unshift({time: leading, label: separatorFormat.format(leading), leading: true});
    }

    return {scale, ticks, minorTicks, separators};
}
