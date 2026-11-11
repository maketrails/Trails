/**
 * Generated history for the timeline redesign lab (`/lab/timeline`): a believable week
 * of a device's life — commutes, lunch walks, bike rides on the weekend, a stretch where
 * the phone was off — cut into episodes that cover every moment from the first to the
 * last one without overlapping.
 *
 * Experimental and self-contained: nothing outside `lib/lab` and `routes/lab` imports
 * it, so the whole lab can be deleted in one go.
 */
import type {MovementItem} from "$lib/api/history/history_repository";

const MINUTE = 60 * 1_000;
const HOUR = 60 * MINUTE;

/** The places the generated person spends time at. Shown through `lab.timeline.places.*`. */
export type PlaceKey = "home" | "work" | "station" | "cafe" | "park" | "lake" | "friends";

/** A stretch spent in one place. */
export interface StayEpisode {
    kind: "stay";
    id: string;
    from: number;
    to: number;
    place: PlaceKey;
}

/** A stretch spent moving from one place to the next. */
export interface MovementEpisode {
    kind: "movement";
    id: string;
    from: number;
    to: number;
    movement: MovementItem;
    origin: PlaceKey;
    destination: PlaceKey;
}

/** A stretch the device reported nothing for. */
export interface GapEpisode {
    kind: "gap";
    id: string;
    from: number;
    to: number;
}

export type LabEpisode = StayEpisode | MovementEpisode | GapEpisode;

export interface LabHistory {
    /** First and last moment there is data for, as epoch milliseconds. */
    oldest: number;
    newest: number;
    /** Oldest first, contiguous from [oldest] to [newest]. */
    episodes: LabEpisode[];
    /** The movement episodes' movements, in the shape the real history hands them out. */
    movements: MovementItem[];
}

/** Rough speed of each way of moving, in metres per second. */
const SPEEDS: Record<string, number> = {walking: 1.3, cycling: 4.6, travel: 11};

/** A small seeded generator, so the lab shows the same week on every load. */
function seeded(seed: number): () => number {
    let state = seed >>> 0;
    return () => {
        state = (state + 0x6d2b79f5) >>> 0;
        let t = state;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4_294_967_296;
    };
}

/**
 * A week (by default) ending at [now]: [days] calendar days, the last one cut off at
 * [now]. Deterministic for a given [seed] and day.
 */
export function createLabHistory(now: Date, days = 7, seed = 7): LabHistory {
    const random = seeded(seed);
    const jitter = (minutes: number) => Math.round(random() * minutes) * MINUTE;

    const firstDay = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (days - 1));
    const oldest = firstDay.getTime();
    const newest = now.getTime();

    const episodes: LabEpisode[] = [];
    let cursor = oldest;
    let place: PlaceKey = "home";

    const stayUntil = (until: number) => {
        if (until <= cursor) return;
        episodes.push({kind: "stay", id: `s${cursor}`, from: cursor, to: until, place});
        cursor = until;
    };

    const move = (type: string, minutes: number, destination: PlaceKey) => {
        const to = cursor + minutes * MINUTE + jitter(minutes / 4);
        const distance = Math.round(((to - cursor) / 1_000) * SPEEDS[type] * (0.8 + random() * 0.3));
        const movement: MovementItem = {id: `m${cursor}`, from: cursor, to, type, distance_meters: distance};
        episodes.push({kind: "movement", id: movement.id, from: cursor, to, movement, origin: place, destination});
        cursor = to;
        place = destination;
    };

    for (let index = 0; index < days; index++) {
        const day = new Date(firstDay.getFullYear(), firstDay.getMonth(), firstDay.getDate() + index);
        const at = (hours: number) => day.getTime() + hours * HOUR;
        const weekend = day.getDay() === 0 || day.getDay() === 6;

        if (weekend) {
            stayUntil(at(9.5) + jitter(60));
            if (index % 2 === 0) {
                move("cycling", 40, "lake");
                stayUntil(cursor + 2 * HOUR + jitter(60));
                move("cycling", 45, "home");
            } else {
                move("walking", 8, "station");
                stayUntil(cursor + 6 * MINUTE);
                move("travel", 55, "friends");
                stayUntil(at(17) + jitter(90));
                move("travel", 60, "home");
            }
        } else {
            stayUntil(at(7.25) + jitter(45));
            move("walking", 11, "station");
            stayUntil(cursor + 4 * MINUTE + jitter(5));
            move("travel", 32, "work");
            stayUntil(at(12) + jitter(30));
            move("walking", 9, "cafe");
            stayUntil(cursor + 40 * MINUTE + jitter(15));
            move("walking", 9, "work");
            stayUntil(at(17) + jitter(60));
            if (index % 3 === 1) {
                move("cycling", 28, "home");
            } else {
                move("walking", 7, "station");
                stayUntil(cursor + 5 * MINUTE);
                move("travel", 35, "home");
            }
            if (index % 2 === 0) {
                stayUntil(cursor + HOUR + jitter(60));
                move("walking", 18, "park");
                stayUntil(cursor + 35 * MINUTE + jitter(20));
                move("walking", 20, "home");
            }
        }
    }
    stayUntil(Math.max(cursor, newest));

    // The phone was off for an afternoon three days ago.
    const gapDay = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 3);
    const gapFrom = gapDay.getTime() + 13.5 * HOUR;
    const withGap = days > 3 ? punchGap(episodes, gapFrom, gapFrom + 2 * HOUR + 40 * MINUTE) : episodes;

    const clipped = clipTo(withGap, oldest, newest);
    return {
        oldest,
        newest,
        episodes: clipped,
        movements: clipped.flatMap((episode) => (episode.kind === "movement" ? [episode.movement] : [])),
    };
}

/** Replaces whatever lies in [from]…[to] with a gap, cutting the episodes at either end. */
function punchGap(episodes: LabEpisode[], from: number, to: number): LabEpisode[] {
    const result: LabEpisode[] = [];
    for (const episode of episodes) {
        if (episode.to <= from || episode.from >= to) {
            result.push(episode);
            continue;
        }
        // A movement cut in half would no longer be a movement anyone made; it goes.
        if (episode.kind !== "stay") continue;
        if (episode.from < from) result.push({...episode, to: from});
        if (episode.to > to) result.push({...episode, id: `s${to}`, from: to});
    }

    // Whatever was dropped around the edges belongs to the gap as well.
    const before = result.filter((episode) => episode.to <= from);
    const after = result.filter((episode) => episode.from >= to);
    const gapFrom = before.length > 0 ? before[before.length - 1].to : from;
    const gapTo = after.length > 0 ? after[0].from : to;
    return [...before, {kind: "gap", id: `g${gapFrom}`, from: gapFrom, to: gapTo}, ...after];
}

/** [episodes] cut to [start]…[end]; a movement running past either end is dropped. */
function clipTo(episodes: LabEpisode[], start: number, end: number): LabEpisode[] {
    const result: LabEpisode[] = episodes
        .filter((episode) => episode.to > start && episode.from < end)
        .filter((episode) => episode.kind !== "movement" || episode.to <= end)
        .map((episode) => ({...episode, from: Math.max(episode.from, start), to: Math.min(episode.to, end)}));

    // What a cut-off movement leaves open is spent where the last one ended.
    const last = result[result.length - 1];
    if (last != null && last.to < end) {
        if (last.kind === "stay") result[result.length - 1] = {...last, to: end};
        else result.push({kind: "stay", id: `s${last.to}`, from: last.to, to: end, place: last.kind === "movement" ? last.destination : "home"});
    }
    return result;
}

/** The episodes reaching into [start]…[end], oldest first. */
export function episodesBetween(episodes: LabEpisode[], start: number, end: number): LabEpisode[] {
    return episodes.filter((episode) => episode.to > start && episode.from < end);
}

/** The episode [time] falls into, or `null` outside the history. */
export function episodeAt(episodes: LabEpisode[], time: number): LabEpisode | null {
    return episodes.find((episode) => time >= episode.from && time < episode.to)
        ?? (episodes.length > 0 && time === episodes[episodes.length - 1].to ? episodes[episodes.length - 1] : null);
}

/** One local calendar day, as the moments it starts and ends at. */
export interface LabDay {
    start: number;
    end: number;
}

/** Every local calendar day [oldest]…[newest] touches, oldest first. */
export function daysOf(oldest: number, newest: number): LabDay[] {
    const result: LabDay[] = [];
    const first = new Date(oldest);
    for (let cursor = new Date(first.getFullYear(), first.getMonth(), first.getDate()); cursor.getTime() <= newest;) {
        const next = new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() + 1);
        result.push({start: cursor.getTime(), end: next.getTime()});
        cursor = next;
    }
    return result;
}

/** How far [movements] went, and for how long, in metres and milliseconds. */
export function totals(movements: MovementItem[]): {distance: number; moving: number} {
    return movements.reduce(
        (sum, movement) => ({distance: sum.distance + movement.distance_meters, moving: sum.moving + movement.to - movement.from}),
        {distance: 0, moving: 0},
    );
}

/**
 * [ms] as hours and minutes in [locale] — "2 hr 5 min" in English, "2 Std., 5 Min." in
 * German — through `Intl` rather than a hand-made pattern.
 */
export function formatDuration(ms: number, locale: string | null | undefined): string {
    const minutes = Math.max(0, Math.round(ms / MINUTE));
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    const unit = (value: number, name: "hour" | "minute") =>
        new Intl.NumberFormat(locale ?? undefined, {style: "unit", unit: name, unitDisplay: "short"}).format(value);

    if (hours === 0) return unit(rest, "minute");
    if (rest === 0) return unit(hours, "hour");
    return new Intl.ListFormat(locale ?? undefined, {style: "narrow", type: "unit"})
        .format([unit(hours, "hour"), unit(rest, "minute")]);
}
