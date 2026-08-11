import {browser} from "$app/environment";
import Dexie, {type Table} from "dexie";
import type {HistoryPoint, HistorySource} from "$lib/api/history/history_repository";

/**
 * IndexedDB cache for a device's location history, via Dexie.
 *
 * Both series are cached, each on its own. What makes that possible is that the server
 * filters an incremental read by *storage* time: the optimizer rewrites stretches of
 * the optimized track under the timestamps of the measurements they came from, so only
 * the storage time reveals them as new. A cached series therefore continues from a
 * cursor instead of from its last position — see `LocationHistoryResponse` on the
 * server side.
 *
 * `localStorage` cannot hold this: a device that recorded for a month reaches ~4 MB of
 * serialized positions, and because `localStorage` is measured in UTF-16 units that is
 * ~8 MB against a ~5 MB origin quota. IndexedDB stores the points as structured data,
 * has a quota measured in a share of the free disk instead, and lets a visit write only
 * what actually changed.
 *
 * One record per position, keyed `[device+source+timestamp]`:
 * - a series comes out of a range query already in timestamp order,
 * - re-storing a position overwrites it instead of duplicating it, which is what makes
 *   the overlapping seam of an incremental read harmless,
 * - and because a write is one transaction, a cached series is never half-updated —
 *   the invariant `history.svelte.ts` relies on, since it presents what it holds as the
 *   complete track.
 */

/** A cached position, plus the series it belongs to. */
interface StoredPoint extends HistoryPoint {
    device: string;
    source: HistorySource;
}

/** How far one cached series has been read. */
interface StoredCursor {
    device: string;
    source: HistorySource;
    cursor: number;
}

class HistoryDatabase extends Dexie {
    points!: Table<StoredPoint, [string, HistorySource, number]>;
    cursors!: Table<StoredCursor, [string, HistorySource]>;

    constructor() {
        super("trails");
        // The plain `device` index is what makes "which devices hold something?" a walk
        // over devices instead of over every cached position.
        this.version(2).stores({
            // The first shape: the raw series only, and no cursor to continue from.
            rawPoints: null,
            points: "[device+source+timestamp], device",
            cursors: "[device+source], device",
        });
    }
}

/**
 * The database, opened on first use. `null` while server-side rendering, where there is
 * no IndexedDB to talk to — every function below then does nothing, and the history is
 * read from the server as if nothing were cached.
 */
let database: HistoryDatabase | null = null;

function open(): HistoryDatabase | null {
    if (!browser) return null;
    if (database == null) {
        forgetLegacyEntries();
        database = new HistoryDatabase();
    }
    return database;
}

/** The key range covering one cached series, from its first timestamp to its last. */
function seriesRange(opened: HistoryDatabase, deviceId: string, source: HistorySource, from = Dexie.minKey) {
    return opened.points
        .where("[device+source+timestamp]")
        .between([deviceId, source, from], [deviceId, source, Dexie.maxKey], true, true);
}

/** A cached series and the cursor to continue it from. */
export interface CachedHistory {
    /** Oldest point first. Never empty — nothing cached is reported as `null` instead. */
    points: HistoryPoint[];
    /** `null` when points are cached but no cursor is, which forces a full read. */
    cursor: number | null;
}

/**
 * The cached [source] series of [deviceId], or `null` when nothing is cached for it
 * (and whenever storage cannot be reached at all).
 */
export async function readCachedHistory(
    deviceId: string,
    source: HistorySource,
): Promise<CachedHistory | null> {
    const opened = open();
    if (opened == null) return null;

    try {
        const [stored, storedCursor] = await Promise.all([
            seriesRange(opened, deviceId, source).toArray(),
            opened.cursors.get([deviceId, source]),
        ]);
        if (stored.length === 0) return null;

        return {
            // Device and series are part of the key, not of what a caller asked for.
            points: stored.map(({device: _device, source: _source, ...point}) => point),
            cursor: storedCursor?.cursor ?? null,
        };
    } catch (e) {
        console.warn("Could not read the cached location history", e);
        return null;
    }
}

/**
 * Applies an answer to the cached [source] series of [deviceId]: everything [fresh]
 * supersedes is dropped, the fresh positions take its place, and [cursor] records where
 * to continue. One transaction, so the series never ends up in a state between the two.
 */
export async function storeCachedHistory(
    deviceId: string,
    source: HistorySource,
    fresh: HistoryPoint[],
    cursor: number | null,
): Promise<void> {
    const supersededFrom = supersededFromTimestamp(fresh);
    if (supersededFrom == null) return;

    const opened = open();
    if (opened == null) return;

    try {
        await opened.transaction("rw", opened.points, opened.cursors, async () => {
            await seriesRange(opened, deviceId, source, supersededFrom).delete();
            await opened.points.bulkPut(fresh.map((point) => ({...point, device: deviceId, source})));
            if (cursor != null) await opened.cursors.put({device: deviceId, source, cursor});
        });
    } catch (e) {
        // Never silently: a cache that cannot be written means every visit downloads the
        // whole history again, and that should be visible rather than just slow.
        console.warn("Could not cache the location history", e);
    }
}

/** Forgets everything cached for [deviceId], both series and their cursors. */
export async function clearCachedHistory(deviceId: string): Promise<void> {
    const opened = open();
    if (opened == null) return;

    try {
        await opened.transaction("rw", opened.points, opened.cursors, async () => {
            await opened.points.where("device").equals(deviceId).delete();
            await opened.cursors.where("device").equals(deviceId).delete();
        });
    } catch (e) {
        console.warn("Could not clear the cached location history", e);
    }
}

/**
 * Forgets every cached history, whichever device it belongs to.
 *
 * Sign-out is the moment for this: a location history is the most personal thing this
 * app holds, and once the session is gone it must not be left behind on a possibly
 * shared computer for the next visitor to find.
 */
export async function clearAllCachedHistories(): Promise<void> {
    const opened = open();
    if (opened == null) return;

    try {
        await opened.transaction("rw", opened.points, opened.cursors, async () => {
            await opened.points.clear();
            await opened.cursors.clear();
        });
    } catch (e) {
        console.warn("Could not clear the cached location histories", e);
    }
}

/**
 * Forgets everything cached for devices that are **not** in [knownDeviceIds] — deleted
 * devices, and devices that stopped being this user's.
 *
 * Nothing else ever removes such an entry: a deleted device's history endpoint answers
 * `403`, which is indistinguishable from a network problem, so the load deliberately
 * keeps showing what it has. Pruning is therefore driven from the one place that knows
 * the full truth, the device list of the webapp socket.
 *
 * Only ever call this with the list of a **successful** load. A device list that is
 * empty because a request failed, because the socket is not up yet or because the
 * browser is offline says nothing about which devices exist — pruning against it would
 * wipe caches that are still wanted. An authoritative list that happens to be empty (a
 * user without devices) is fine.
 */
export async function pruneCachedHistories(knownDeviceIds: Iterable<string>): Promise<void> {
    const opened = open();
    if (opened == null) return;

    try {
        // One key per cached device rather than one per position: `uniqueKeys` walks the
        // `device` index by distinct value. Cursors are walked too, so a series whose
        // points are already gone does not leave its cursor behind.
        const cached = await Promise.all([
            opened.points.orderBy("device").uniqueKeys(),
            opened.cursors.orderBy("device").uniqueKeys(),
        ]);
        const known = new Set(knownDeviceIds);

        for (const deviceId of new Set(cached.flat() as string[])) {
            if (!known.has(deviceId)) await clearCachedHistory(deviceId);
        }
    } catch (e) {
        console.warn("Could not prune the cached location histories", e);
    }
}

/**
 * The timestamp from which [fresh] supersedes what a cache holds, or `null` when it
 * supersedes nothing because it is empty.
 *
 * An answer from the history endpoints is not a pure addition. The optimizer rebuilds
 * stretches of the optimized track, and a rebuild can end up with *fewer* positions
 * than the generation before it, so everything from the first fresh position onwards
 * has to give way to what came back rather than being merged with it.
 */
function supersededFromTimestamp(fresh: HistoryPoint[]): number | null {
    return fresh.length === 0 ? null : fresh[0].timestamp;
}

/**
 * The cached points with [fresh] applied on top, oldest first — the in-memory twin of
 * what [storeCachedHistory] does to the cache.
 *
 * Both sides are ordered oldest first and [fresh] replaces the cached tail from its own
 * first position onwards, so cutting there and appending needs no sorting.
 */
export function applyFreshPoints(cached: HistoryPoint[], fresh: HistoryPoint[]): HistoryPoint[] {
    const supersededFrom = supersededFromTimestamp(fresh);
    if (supersededFrom == null) return cached;

    return [...cached.filter((point) => point.timestamp < supersededFrom), ...fresh];
}

/**
 * Drops the entries of the first, `localStorage`-based version of this cache. It never
 * shipped, but a development browser can still hold a few hundred kilobytes of
 * positions under those keys, and location data must not be left lying around just
 * because the storage behind the cache changed.
 */
function forgetLegacyEntries(): void {
    const legacyPrefix = "trails.history.raw.";
    try {
        const keys: string[] = [];
        for (let index = 0; index < localStorage.length; index++) {
            const storedKey = localStorage.key(index);
            if (storedKey?.startsWith(legacyPrefix)) keys.push(storedKey);
        }
        for (const storedKey of keys) localStorage.removeItem(storedKey);
    } catch {
        // Storage denied — then nothing was ever written under those keys either.
    }
}
