import type {HistoryPoint} from "$lib/api/history/history_repository";

/**
 * Turning a recorded history into something a map can draw at sixty frames a
 * second.
 *
 * A history is unbounded — hundreds of thousands of points, millions for a device
 * that has been recording for years — while a line on screen is a few thousand
 * pixels long. Everything past that is detail nobody can see and every frame has to
 * carry, so the track is thinned once, when it arrives, and every later redraw works
 * on the thinned copy.
 *
 * The result is kept in flat typed arrays rather than objects: they survive being
 * handed around without being copied, and reading a million of them costs a fraction
 * of what walking a million objects does.
 */

/** Longest a drawn track may be. A screen holds a few thousand distinguishable points. */
export const DISPLAY_BUDGET = 20_000;

/**
 * Anything longer than this between two consecutive points is a recording gap:
 * where the device actually went in between is unknown, so that stretch is drawn as
 * a faint dotted hint instead of a solid line.
 */
export const TRAIL_GAP_MS = 60_000;

/**
 * Where a position sits *on* a drawn track: the stretch starting at [index], and how
 * far along it. Anywhere between two kept points, which is what a cursor resolves to.
 */
export interface TrackPosition {
    index: number;
    /** 0 at the stretch's start, 1 at its end. */
    fraction: number;
}

/** A history thinned down to what a map can draw, in parallel arrays. */
export interface DisplayTrack {
    /** `[longitude, latitude]` per kept point, in order. */
    coordinates: number[][];
    /** When each kept point was recorded. */
    times: Float64Array;
    /** `gaps[i]` marks the stretch from point `i - 1` to `i` as a recording gap. */
    gaps: Uint8Array;
    /** `raws[i]` marks that same stretch as an unoptimized measurement. */
    raws: Uint8Array;
    /**
     * Where each kept point sits in the history it was thinned from. Thinning drops
     * points but nothing else about them, so this is how a drawn position is traded
     * back for everything it was recorded with — accuracy, bearing, charge level.
     */
    sources: Int32Array;
    /** How many points the history held before thinning. */
    recorded: number;
}

export const EMPTY_TRACK: DisplayTrack = {
    coordinates: [],
    times: new Float64Array(0),
    gaps: new Uint8Array(0),
    raws: new Uint8Array(0),
    sources: new Int32Array(0),
    recorded: 0,
};

/**
 * Whether a point has to be kept whatever the thinning says: the ends of the track,
 * and both sides of every change in how a stretch is drawn.
 *
 * Dropping one of those would move a gap or a raw stretch onto ground it never
 * covered, which is a lie about where the device was — unlike dropping a point in
 * the middle of a straight run, which is merely less detail.
 */
function isFixed(points: HistoryPoint[], index: number, gap: boolean, nextGap: boolean): boolean {
    if (index === 0 || index === points.length - 1) return true;
    if (gap || nextGap) return true;
    return points[index].is_raw !== points[index + 1].is_raw;
}

/**
 * The history as a drawn track, thinned to at most [budget] points.
 *
 * Thinning is by distance: a point closer to the last kept one than the tolerance
 * says nothing a reader could see, so it goes. The tolerance starts at a fraction of
 * the track's own extent and doubles until the track fits the budget, which keeps
 * both a walk around the block and a decade of driving inside it.
 *
 * One pass costs one read per point, so even a million of them is a single sweep of
 * a few tens of milliseconds — paid once, when the history arrives.
 */
export function displayTrack(points: HistoryPoint[], budget = DISPLAY_BUDGET): DisplayTrack {
    if (points.length === 0) return EMPTY_TRACK;

    // Degrees, not metres: the comparison only has to be consistent along one track,
    // and converting every point would cost more than it tells us. Longitude is
    // scaled by the latitude's cosine so it counts for what it is worth on screen.
    const scale = Math.cos((points[0].latitude * Math.PI) / 180) || 1;

    let minLng = Infinity;
    let maxLng = -Infinity;
    let minLat = Infinity;
    let maxLat = -Infinity;
    for (const point of points) {
        if (point.longitude < minLng) minLng = point.longitude;
        if (point.longitude > maxLng) maxLng = point.longitude;
        if (point.latitude < minLat) minLat = point.latitude;
        if (point.latitude > maxLat) maxLat = point.latitude;
    }

    // A thousandth of the track's extent is well under a pixel at the zoom that shows
    // the whole of it. The longer the history, the more of it has to go to fit the
    // budget, so the first guess is widened by how far over the budget we are —
    // otherwise a million points would be swept several times over before landing.
    const extent = Math.max((maxLng - minLng) * scale, maxLat - minLat);
    let tolerance = (extent / 2_000) * Math.max(1, Math.sqrt(points.length / budget));

    for (let attempt = 0; ; attempt++) {
        const track = thin(points, tolerance, scale);
        // The fixed points alone can exceed the budget — a history full of gaps — and
        // no tolerance would ever bring that down. Ten doublings is a factor of a
        // thousand; past that the track is as short as thinning can make it.
        if (track.coordinates.length <= budget || attempt >= 10) return track;
        tolerance = tolerance > 0 ? tolerance * 2 : extent / 1_000;
    }
}

/** One thinning pass: keep the fixed points, and whatever else is far enough apart. */
function thin(points: HistoryPoint[], tolerance: number, scale: number): DisplayTrack {
    const coordinates: number[][] = [];
    const times: number[] = [];
    const gaps: number[] = [];
    const raws: number[] = [];
    const sources: number[] = [];

    const squared = tolerance * tolerance;
    let lastLng = 0;
    let lastLat = 0;

    // Carried over from the points that were dropped: a stretch is a gap, or raw, if
    // anything it was made of was.
    let pendingGap = false;
    let pendingRaw = false;

    for (let i = 0; i < points.length; i++) {
        const point = points[i];
        const previous = i > 0 ? points[i - 1] : null;
        const gap = previous != null && point.timestamp - previous.timestamp > TRAIL_GAP_MS;
        const nextGap = i + 1 < points.length && points[i + 1].timestamp - point.timestamp > TRAIL_GAP_MS;

        pendingGap = pendingGap || gap;
        pendingRaw = pendingRaw || (previous != null && point.is_raw);

        if (coordinates.length > 0 && !isFixed(points, i, gap, nextGap)) {
            const dx = (point.longitude - lastLng) * scale;
            const dy = point.latitude - lastLat;
            if (dx * dx + dy * dy < squared) continue;
        }

        coordinates.push([point.longitude, point.latitude]);
        times.push(point.timestamp);
        sources.push(i);
        gaps.push(coordinates.length === 1 ? 0 : pendingGap ? 1 : 0);
        raws.push(coordinates.length === 1 ? 0 : pendingRaw ? 1 : 0);
        lastLng = point.longitude;
        lastLat = point.latitude;
        pendingGap = false;
        pendingRaw = false;
    }

    return {
        coordinates,
        times: Float64Array.from(times),
        gaps: Uint8Array.from(gaps),
        raws: Uint8Array.from(raws),
        sources: Int32Array.from(sources),
        recorded: points.length,
    };
}

/**
 * Where [time] falls on the track: the stretch that contains it and how far along it
 * that moment sits, or `null` when the track does not reach it.
 *
 * A binary search rather than a scan — this answers a cursor moving along a timeline,
 * so it runs as often as the pointer moves.
 */
export function positionAtTime(track: DisplayTrack, time: number): TrackPosition | null {
    const {times} = track;
    if (times.length === 0) return null;
    if (time <= times[0]) return {index: 0, fraction: 0};
    if (time >= times[times.length - 1]) return {index: Math.max(0, times.length - 2), fraction: 1};

    let low = 0;
    let high = times.length - 1;
    while (high - low > 1) {
        const middle = (low + high) >> 1;
        if (times[middle] <= time) low = middle;
        else high = middle;
    }

    const span = times[high] - times[low];
    return {index: low, fraction: span > 0 ? (time - times[low]) / span : 0};
}

/** The coordinate [position] stands at, interpolated along its stretch. */
export function coordinateAt(track: DisplayTrack, position: TrackPosition): [number, number] | null {
    const from = track.coordinates[position.index];
    if (from == null) return null;

    const to = track.coordinates[position.index + 1];
    if (to == null) return [from[0], from[1]];

    return [
        from[0] + (to[0] - from[0]) * position.fraction,
        from[1] + (to[1] - from[1]) * position.fraction,
    ];
}

/** The recorded point a drawn position stands closest to — the whole of it, not just where. */
export function recordedAt(track: DisplayTrack, points: HistoryPoint[], position: TrackPosition): HistoryPoint | null {
    const index = position.fraction >= 0.5 ? position.index + 1 : position.index;
    const source = track.sources[Math.min(index, track.sources.length - 1)];
    return points[source] ?? null;
}
