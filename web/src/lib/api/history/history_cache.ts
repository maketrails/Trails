import {browser} from "$app/environment";
import type {HistoryPoint} from "$lib/api/history/history_repository";

/**
 * `localStorage` cache for the **raw** location history of a device.
 *
 * The raw series is the only one worth caching: it is append-only — the optimizer
 * derives its own points and never touches the measurements — so a cached prefix
 * stays valid and only the tail has to be read again. The optimized series is
 * rebuilt whenever the optimizer catches up, which would leave a cache holding
 * positions of an older generation.
 *
 * A history of years is far too big to re-download on every visit, and equally too
 * big to always fit in a 5 MB origin quota, so a cache that cannot be stored is
 * simply dropped: the next visit reads the full history again. What must never
 * happen is storing *part* of a history, because the merge below would then present
 * a truncated track as the complete one.
 */

/** Bumped whenever the stored shape changes; entries of any other version are dropped. */
const VERSION = 1;

const KEY_PREFIX = "trails.history.raw.";

/**
 * Entries larger than this are not stored at all. Browsers grant an origin around
 * 5 MB and the map, the socket snapshots and the other devices' histories share it,
 * so one device may not claim all of it.
 */
const MAX_SERIALIZED_LENGTH = 2_000_000;

interface CacheEntry {
    version: number;
    points: HistoryPoint[];
}

function key(deviceId: string): string {
    return `${KEY_PREFIX}${deviceId}`;
}

/**
 * The cached raw history of [deviceId], oldest point first, or `null` when there is
 * nothing usable stored. Anything unreadable (another version, truncated JSON,
 * hand-edited storage) is discarded rather than repaired — the full history is one
 * request away.
 */
export function readCachedHistory(deviceId: string): HistoryPoint[] | null {
    if (!browser) return null;

    let raw: string | null;
    try {
        raw = localStorage.getItem(key(deviceId));
    } catch {
        // Storage can be denied outright (private mode, blocked cookies).
        return null;
    }
    if (raw == null) return null;

    try {
        const entry = JSON.parse(raw) as CacheEntry;
        if (entry?.version !== VERSION || !Array.isArray(entry.points) || entry.points.length === 0) {
            clearCachedHistory(deviceId);
            return null;
        }
        return entry.points;
    } catch {
        clearCachedHistory(deviceId);
        return null;
    }
}

/**
 * Stores [points] as the complete raw history of [deviceId]. Only ever call this
 * with a full history — see the note on truncation above.
 *
 * An empty history is not stored: "nothing cached" and "cached, and there is
 * nothing" would otherwise be indistinguishable, and a device without positions is
 * the cheap case anyway.
 */
export function writeCachedHistory(deviceId: string, points: HistoryPoint[]): void {
    if (!browser) return;
    if (points.length === 0) {
        clearCachedHistory(deviceId);
        return;
    }

    const entry: CacheEntry = {version: VERSION, points};
    const serialized = JSON.stringify(entry);
    if (serialized.length > MAX_SERIALIZED_LENGTH) {
        clearCachedHistory(deviceId);
        return;
    }

    try {
        localStorage.setItem(key(deviceId), serialized);
    } catch {
        // Out of quota (or storage denied): leave nothing behind, so the next read
        // cannot pick up a half-written or stale entry.
        clearCachedHistory(deviceId);
    }
}

/** Forgets the cached raw history of [deviceId]. */
export function clearCachedHistory(deviceId: string): void {
    if (!browser) return;
    try {
        localStorage.removeItem(key(deviceId));
    } catch {
        // Nothing to do — a cache we cannot clear is a cache we never read either.
    }
}

/**
 * The device ids that currently hold a cached history, as a snapshot — removing an
 * entry while walking `localStorage` would shift the indices of the keys behind it.
 * `null` means storage could not be read at all.
 */
function cachedDeviceIds(): string[] | null {
    try {
        const deviceIds: string[] = [];
        for (let index = 0; index < localStorage.length; index++) {
            const storedKey = localStorage.key(index);
            if (storedKey == null || !storedKey.startsWith(KEY_PREFIX)) continue;
            deviceIds.push(storedKey.slice(KEY_PREFIX.length));
        }
        return deviceIds;
    } catch {
        return null;
    }
}

/**
 * Forgets every cached history, whichever device it belongs to.
 *
 * Sign-out is the moment for this: a location history is the most personal thing
 * this app holds, and once the session is gone it must not be left behind on a
 * possibly shared computer for the next visitor to find.
 */
export function clearAllCachedHistories(): void {
    if (!browser) return;
    for (const deviceId of cachedDeviceIds() ?? []) clearCachedHistory(deviceId);
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
export function pruneCachedHistories(knownDeviceIds: Iterable<string>): void {
    if (!browser) return;

    const known = new Set(knownDeviceIds);
    for (const deviceId of cachedDeviceIds() ?? []) {
        if (!known.has(deviceId)) clearCachedHistory(deviceId);
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
