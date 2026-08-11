import {browser} from "$app/environment";
import Dexie, {type EntityTable} from "dexie";
import type {HistoryPoint} from "$lib/api/history/history_repository";

/**
 * IndexedDB cache for the **raw** location history of a device, via Dexie.
 *
 * The raw series is the only one worth caching: it is append-only — the optimizer
 * derives its own points and never touches the measurements — so a cached prefix
 * stays valid and only the tail has to be read again. The optimized series is
 * rebuilt whenever the optimizer catches up, which would leave a cache holding
 * positions of an older generation.
 *
 * `localStorage` cannot hold this: a device that recorded for a month reaches ~4 MB
 * of serialized positions, and because `localStorage` is measured in UTF-16 units
 * that is ~8 MB against a ~5 MB origin quota. IndexedDB stores the points as
 * structured data, has a quota measured in a share of the free disk instead, and
 * lets a visit write **only the new tail** rather than rewriting the whole history.
 *
 * One record per position, with `[device+timestamp]` as the primary key:
 * - a device's points come out of a range query already in timestamp order,
 * - re-storing a point the cache already holds overwrites it instead of duplicating
 *   it, which is what makes the overlapping seam of an incremental read harmless,
 * - and because a bulk write is one transaction, a cached history is never a partial
 *   one — the invariant the merge in `history.svelte.ts` relies on, since it presents
 *   what it holds as the complete track.
 */

/** A cached position: a [HistoryPoint] plus the device it was recorded by. */
interface StoredPoint extends HistoryPoint {
    device: string;
}

class HistoryDatabase extends Dexie {
    rawPoints!: EntityTable<StoredPoint>;

    constructor() {
        super("trails");
        // The extra `device` index is what makes "which devices hold something?" a
        // walk over devices instead of over every cached position.
        this.version(1).stores({rawPoints: "[device+timestamp], device"});
    }
}

/**
 * The database, opened on first use. `null` while server-side rendering, where there
 * is no IndexedDB to talk to — every function below then does nothing, and the
 * history is read from the server as if nothing were cached.
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

/**
 * The key range covering every point of one device: from the device's first possible
 * timestamp to its last.
 */
function deviceRange(deviceId: string) {
    return open()?.rawPoints.where("[device+timestamp]").between(
        [deviceId, Dexie.minKey],
        [deviceId, Dexie.maxKey],
    );
}

/**
 * The cached raw history of [deviceId], oldest point first, or `null` when nothing is
 * cached for it (and whenever storage cannot be reached at all).
 */
export async function readCachedHistory(deviceId: string): Promise<HistoryPoint[] | null> {
    try {
        const stored = await deviceRange(deviceId)?.toArray();
        if (stored == null || stored.length === 0) return null;
        // The device id is part of the key, not of what a caller asked for.
        return stored.map(({device: _device, ...point}) => point);
    } catch (e) {
        console.warn("Could not read the cached location history", e);
        return null;
    }
}

/**
 * Adds [points] to the cached history of [deviceId]. Only the positions a load
 * actually read have to be passed — the ones already cached stay untouched, and a
 * point handed over twice (the inclusive seam of an incremental read) overwrites its
 * own record.
 */
export async function appendCachedHistory(deviceId: string, points: HistoryPoint[]): Promise<void> {
    if (points.length === 0) return;

    try {
        await open()?.rawPoints.bulkPut(points.map((point) => ({...point, device: deviceId})));
    } catch (e) {
        // Never silently: a cache that cannot be written means every visit downloads
        // the whole history again, and that should be visible rather than just slow.
        console.warn("Could not cache the location history", e);
    }
}

/** Forgets the cached raw history of [deviceId]. */
export async function clearCachedHistory(deviceId: string): Promise<void> {
    try {
        await deviceRange(deviceId)?.delete();
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
    try {
        await open()?.rawPoints.clear();
    } catch (e) {
        console.warn("Could not clear the cached location histories", e);
    }
}

/**
 * Forgets the cached history of every device that is **not** in [knownDeviceIds] —
 * deleted devices, and devices that stopped being this user's.
 *
 * Nothing else ever removes such an entry: a deleted device's history endpoint
 * answers `403`, which is indistinguishable from a network problem, so the load
 * deliberately keeps showing what it has. Pruning is therefore driven from the one
 * place that knows the full truth, the device list of the webapp socket.
 *
 * Only ever call this with the list of a **successful** load. A device list that is
 * empty because a request failed, because the socket is not up yet or because the
 * browser is offline says nothing about which devices exist — pruning against it
 * would wipe caches that are still wanted. An authoritative list that happens to be
 * empty (a user without devices) is fine.
 */
export async function pruneCachedHistories(knownDeviceIds: Iterable<string>): Promise<void> {
    const opened = open();
    if (opened == null) return;

    try {
        // One key per cached device rather than one per position: `uniqueKeys` walks
        // the `device` index by distinct value.
        const cached = await opened.rawPoints.orderBy("device").uniqueKeys();
        const known = new Set(knownDeviceIds);

        for (const key of cached) {
            const deviceId = key as string;
            if (!known.has(deviceId)) await clearCachedHistory(deviceId);
        }
    } catch (e) {
        console.warn("Could not prune the cached location histories", e);
    }
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

/**
 * The cached points followed by the freshly fetched ones, oldest first.
 *
 * The `since` bound of the history endpoints is inclusive, so the two overlap by at
 * least the point the cache ended on. A raw position is unique per device and
 * timestamp (the `(device, timestamp, is_raw)` index says so), which makes the
 * timestamp the identity to deduplicate on; where both sides carry one, the fresh
 * point wins.
 */
export function mergeHistoryPoints(cached: HistoryPoint[], fresh: HistoryPoint[]): HistoryPoint[] {
    const byTimestamp = new Map<number, HistoryPoint>();
    for (const point of cached) byTimestamp.set(point.timestamp, point);
    for (const point of fresh) byTimestamp.set(point.timestamp, point);
    return [...byTimestamp.values()].sort((a, b) => a.timestamp - b.timestamp);
}
