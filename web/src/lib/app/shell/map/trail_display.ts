import type {HistoryPoint} from "$lib/api/history/history_repository";

/**
 * Turning a recorded history into something a map can draw at sixty frames a
 * second, without throwing any of it away.
 *
 * A history is unbounded — hundreds of thousands of points, millions for a device
 * that has been recording for years — while a line on screen is a few thousand
 * pixels long. Handing all of it over on every redraw is what makes a long history
 * unusable; handing over a permanently thinned copy instead would mean the detail is
 * gone the moment somebody zooms in to look for it.
 *
 * So nothing is discarded. What is drawn is chosen for the view: the rung of detail
 * that is finer than a screen pixel at the current zoom, clipped to what is actually
 * on screen. A rung is built the first time a zoom asks for one and then kept, each
 * thinned from the nearest finer one, so the work is done once and reused.
 */

/** Most points a single draw may hand to the map. */
export const DISPLAY_BUDGET = 20_000;

/**
 * Anything longer than this between two consecutive points is a recording gap:
 * where the device actually went in between is unknown, so that stretch is drawn as
 * a faint dotted hint instead of a solid line.
 */
export const TRAIL_GAP_MS = 60_000;

/** Finest rung there is, in degrees — about ten centimetres, past any GPS accuracy. */
const FINEST_TOLERANCE = 1e-6;

/** How many rungs are kept before the finest ones are let go of again. */
const MAX_RUNGS = 10;

/**
 * How many consecutive points share one bounding box. Clipping a fine rung would
 * otherwise walk a million points to find the handful on screen; with a box per
 * chunk it walks the boxes instead and only opens the ones it has to.
 */
const CHUNK = 64;

/** One degree of latitude in metres, near enough for choosing a rung. */
const METRES_PER_DEGREE = 111_320;

/** What the web mercator projection covers per pixel at zoom 0, in metres. */
const METRES_PER_PIXEL_AT_ZERO = 156_543.03392;

/**
 * Where a position sits *on* a drawn track: the stretch starting at [index], and how
 * far along it. Anywhere between two drawn points, which is what a cursor resolves to.
 */
export interface TrackPosition {
    index: number;
    /** 0 at the stretch's start, 1 at its end. */
    fraction: number;
}

/** A stretch of history as it is handed to the map, in parallel arrays. */
export interface DisplayTrack {
    /** `[longitude, latitude]` per drawn point, in order. */
    coordinates: number[][];
    /** When each drawn point was recorded. */
    times: Float64Array;
    /** `gaps[i]` marks the stretch from point `i - 1` to `i` as a recording gap. */
    gaps: Uint8Array;
    /** `raws[i]` marks that same stretch as an unoptimized measurement. */
    raws: Uint8Array;
    /**
     * `breaks[i]` marks that there is no stretch from point `i - 1` to `i` at all:
     * what lay between them was clipped away for being off screen, and drawing a line
     * across it would claim a journey that never happened.
     */
    breaks: Uint8Array;
    /**
     * Where each drawn point sits in the history it came from. Choosing a rung leaves
     * points out but changes nothing about the ones it keeps, so this is how a drawn
     * position is traded back for everything it was recorded with.
     */
    sources: Int32Array;
    /** How many points the history holds in total. */
    recorded: number;
}

export const EMPTY_TRACK: DisplayTrack = {
    coordinates: [],
    times: new Float64Array(0),
    gaps: new Uint8Array(0),
    raws: new Uint8Array(0),
    breaks: new Uint8Array(0),
    sources: new Int32Array(0),
    recorded: 0,
};

/** What is on screen: `[west, south, east, north]`. */
export type TrailBounds = [number, number, number, number];

export interface TrailView {
    /** How much ground one screen pixel covers, in degrees. Zero draws every point. */
    tolerance: number;
    /** What to clip to, or `null` to draw the track wherever it runs. */
    bounds: TrailBounds | null;
    budget?: number;
}

/**
 * How much ground a screen pixel covers at [zoom] and [latitude], in degrees — below
 * which a reader cannot tell two positions apart.
 */
export function toleranceFor(zoom: number, latitude: number, pixels = 1): number {
    const metres = (METRES_PER_PIXEL_AT_ZERO * Math.cos((latitude * Math.PI) / 180)) / Math.pow(2, zoom);
    return (metres * pixels) / METRES_PER_DEGREE;
}

/** One rung of detail, flat rather than in objects: a million of these is 60 MB, not 600. */
interface Rung {
    tolerance: number;
    /** Longitude and latitude interleaved. */
    coordinates: Float64Array;
    times: Float64Array;
    gaps: Uint8Array;
    raws: Uint8Array;
    sources: Int32Array;
    length: number;
    /** West, south, east and north of every {@link CHUNK} points, in that order. */
    boxes: Float64Array;
}

/** The history at every rung of detail a view has asked for so far. */
export interface TrailSource {
    readonly recorded: number;
    /** The stretch to draw for [view]. */
    drawnFor(view: TrailView): DisplayTrack;
}

/** Whether the stretch ending at [index] is a recording gap. */
const isGap = (points: HistoryPoint[], index: number) =>
    index > 0 && points[index].timestamp - points[index - 1].timestamp > TRAIL_GAP_MS;

/**
 * Whether a point has to be kept whatever the thinning says: the ends of the track,
 * and both sides of every change in how a stretch is drawn.
 *
 * Dropping one of those would move a gap or a raw stretch onto ground it never
 * covered, which is a lie about where the device was — unlike leaving out a point in
 * the middle of a straight run, which is merely less detail.
 */
function isFixedPoint(points: HistoryPoint[], index: number): boolean {
    if (index <= 0 || index >= points.length - 1) return true;
    if (isGap(points, index) || isGap(points, index + 1)) return true;
    return points[index].is_raw !== points[index + 1].is_raw;
}

export function createTrailSource(points: HistoryPoint[]): TrailSource {
    // Degrees, not metres: the comparison only has to be consistent along one track,
    // and converting every point would cost more than it tells us. Longitude is
    // scaled by the latitude's cosine so it counts for what it is worth on screen.
    const scale = points.length > 0 ? Math.cos((points[0].latitude * Math.PI) / 180) || 1 : 1;

    /** Rungs already built, by how many doublings above the finest they stand. */
    const rungs = new Map<number, Rung>();

    /** Which rung a tolerance falls on. Doubling steps, so a small zoom reuses a rung. */
    const stepFor = (tolerance: number) =>
        tolerance <= FINEST_TOLERANCE ? 0 : Math.ceil(Math.log2(tolerance / FINEST_TOLERANCE));

    /** The recorded history as a rung. Built at most once, and only if a view asks. */
    let baseRung: Rung | null = null;

    function base(): Rung {
        if (baseRung != null) return baseRung;

        const coordinates = new Float64Array(points.length * 2);
        const times = new Float64Array(points.length);
        const gaps = new Uint8Array(points.length);
        const raws = new Uint8Array(points.length);
        const sources = new Int32Array(points.length);

        for (let i = 0; i < points.length; i++) {
            const point = points[i];
            coordinates[i * 2] = point.longitude;
            coordinates[i * 2 + 1] = point.latitude;
            times[i] = point.timestamp;
            gaps[i] = isGap(points, i) ? 1 : 0;
            raws[i] = i > 0 && point.is_raw ? 1 : 0;
            sources[i] = i;
        }

        baseRung = {
            tolerance: 0,
            coordinates,
            times,
            gaps,
            raws,
            sources,
            length: points.length,
            boxes: boxesOf(coordinates, points.length)
        };
        return baseRung;
    }

    /** [from] with everything closer together than [tolerance] left out. */
    function thin(from: Rung, tolerance: number): Rung {
        const kept: number[] = [];
        const squared = tolerance * tolerance;
        const gaps = new Uint8Array(from.length);
        const raws = new Uint8Array(from.length);

        let lastLng = 0;
        let lastLat = 0;
        // Carried over from the points left out: a stretch is a gap, or raw, if
        // anything it was made of was.
        let pendingGap = 0;
        let pendingRaw = 0;

        for (let i = 0; i < from.length; i++) {
            pendingGap |= from.gaps[i];
            pendingRaw |= from.raws[i];

            if (kept.length > 0 && !isFixedPoint(points, from.sources[i])) {
                const dx = (from.coordinates[i * 2] - lastLng) * scale;
                const dy = from.coordinates[i * 2 + 1] - lastLat;
                if (dx * dx + dy * dy < squared) continue;
            }

            gaps[kept.length] = kept.length === 0 ? 0 : pendingGap;
            raws[kept.length] = kept.length === 0 ? 0 : pendingRaw;
            kept.push(i);
            lastLng = from.coordinates[i * 2];
            lastLat = from.coordinates[i * 2 + 1];
            pendingGap = 0;
            pendingRaw = 0;
        }

        const coordinates = new Float64Array(kept.length * 2);
        const times = new Float64Array(kept.length);
        const sources = new Int32Array(kept.length);
        for (let k = 0; k < kept.length; k++) {
            coordinates[k * 2] = from.coordinates[kept[k] * 2];
            coordinates[k * 2 + 1] = from.coordinates[kept[k] * 2 + 1];
            times[k] = from.times[kept[k]];
            sources[k] = from.sources[kept[k]];
        }

        return {
            tolerance,
            coordinates,
            times,
            gaps: gaps.slice(0, kept.length),
            raws: raws.slice(0, kept.length),
            sources,
            length: kept.length,
            boxes: boxesOf(coordinates, kept.length),
        };
    }

    /** The rung [step] doublings up, built from the nearest finer one already there. */
    function rungAt(step: number): Rung {
        if (step <= 0) return base();

        const existing = rungs.get(step);
        if (existing != null) return existing;

        // Thinning from a rung rather than from the recorded history is what keeps
        // this cheap: each one is a fraction of the one below it, so building the
        // tenth costs barely more than building the first.
        let from: Rung | null = null;
        for (let finer = step - 1; finer > 0; finer--) {
            const candidate = rungs.get(finer);
            if (candidate != null) {
                from = candidate;
                break;
            }
        }

        const rung = thin(from ?? base(), FINEST_TOLERANCE * Math.pow(2, step));
        rungs.set(step, rung);

        // Only so many are kept. The finest are the largest, and the ones a reader
        // leaves behind as soon as they zoom back out.
        while (rungs.size > MAX_RUNGS) {
            const finest = Math.min(...rungs.keys());
            if (finest === step) break;
            rungs.delete(finest);
        }
        return rung;
    }

    /** One bounding box per chunk of points, so a clip can skip whole stretches. */
    function boxesOf(coordinates: Float64Array, length: number): Float64Array {
        const chunks = Math.ceil(length / CHUNK);
        const boxes = new Float64Array(chunks * 4);

        for (let chunk = 0; chunk < chunks; chunk++) {
            let west = Infinity;
            let south = Infinity;
            let east = -Infinity;
            let north = -Infinity;

            const from = chunk * CHUNK;
            const to = Math.min(from + CHUNK, length);
            for (let i = from; i < to; i++) {
                const lng = coordinates[i * 2];
                const lat = coordinates[i * 2 + 1];
                if (lng < west) west = lng;
                if (lng > east) east = lng;
                if (lat < south) south = lat;
                if (lat > north) north = lat;
            }

            boxes[chunk * 4] = west;
            boxes[chunk * 4 + 1] = south;
            boxes[chunk * 4 + 2] = east;
            boxes[chunk * 4 + 3] = north;
        }
        return boxes;
    }

    /** Whether the chunk's box lies wholly off screen. */
    const chunkMisses = (rung: Rung, chunk: number, bounds: TrailBounds) =>
        rung.boxes[chunk * 4] > bounds[2]
        || rung.boxes[chunk * 4 + 2] < bounds[0]
        || rung.boxes[chunk * 4 + 1] > bounds[3]
        || rung.boxes[chunk * 4 + 3] < bounds[1];

    /**
     * Roughly how many points of [rung] the view would keep, counted off the chunk
     * boxes alone. An overestimate — a chunk that reaches into the view rarely has all
     * of its points inside — but it costs a sixty-fourth of a clip, which is what lets
     * the right rung be found without clipping every candidate on the way.
     */
    function reach(rung: Rung, bounds: TrailBounds | null): number {
        if (bounds == null) return rung.length;

        let count = 0;
        const chunks = rung.boxes.length / 4;
        for (let chunk = 0; chunk < chunks; chunk++) {
            if (chunkMisses(rung, chunk, bounds)) continue;
            count += Math.min(CHUNK, rung.length - chunk * CHUNK);
        }
        return count;
    }

    const within = (bounds: TrailBounds, lng: number, lat: number) =>
        lng >= bounds[0] && lng <= bounds[2] && lat >= bounds[1] && lat <= bounds[3];

    /**
     * The rung cut down to what is on screen. A point just outside is kept when a
     * neighbour is inside, so a line entering the view comes from off screen rather
     * than starting at its edge; everything else becomes a break.
     *
     * Whole chunks whose box misses the view are stepped over — all their points are
     * outside, so only the two at their ends can be somebody's inside neighbour, and
     * only those two are looked at. That is what makes clipping a million-point rung
     * cost about as much as clipping the handful actually on screen.
     */
    function clip(rung: Rung, bounds: TrailBounds | null): DisplayTrack {
        const coordinates: number[][] = [];
        const times = new Float64Array(rung.length);
        const gaps = new Uint8Array(rung.length);
        const raws = new Uint8Array(rung.length);
        const breaks = new Uint8Array(rung.length);
        const sources = new Int32Array(rung.length);

        let carriedGap = 0;
        let carriedRaw = 0;
        let previous = -1;

        const keep = (i: number) => {
            const at = coordinates.length;
            coordinates.push([rung.coordinates[i * 2], rung.coordinates[i * 2 + 1]]);
            times[at] = rung.times[i];
            sources[at] = rung.sources[i];
            gaps[at] = at === 0 ? 0 : carriedGap;
            raws[at] = at === 0 ? 0 : carriedRaw;
            breaks[at] = at > 0 && previous !== i - 1 ? 1 : 0;
            previous = i;
            carriedGap = 0;
            carriedRaw = 0;
        };

        /** Whether [i] is on screen, or next to a point that is. */
        const isNear = (i: number) => {
            if (bounds == null) return true;
            if (within(bounds, rung.coordinates[i * 2], rung.coordinates[i * 2 + 1])) return true;
            if (i > 0 && within(bounds, rung.coordinates[i * 2 - 2], rung.coordinates[i * 2 - 1])) return true;
            return i + 1 < rung.length
                && within(bounds, rung.coordinates[i * 2 + 2], rung.coordinates[i * 2 + 3]);
        };

        for (let i = 0; i < rung.length; ) {
            const chunk = (i / CHUNK) | 0;
            const from = chunk * CHUNK;
            const to = Math.min(from + CHUNK, rung.length);

            const misses = bounds != null && chunkMisses(rung, chunk, bounds);

            if (misses) {
                // Everything in here is off screen. Its flags still have to be carried
                // across, and its two ends can still neighbour a point that is on.
                for (let k = i; k < to; k++) {
                    carriedGap |= rung.gaps[k];
                    carriedRaw |= rung.raws[k];
                }
                if (isNear(i)) keep(i);
                if (to - 1 > i && isNear(to - 1)) keep(to - 1);
                i = to;
                continue;
            }

            for (; i < to; i++) {
                carriedGap |= rung.gaps[i];
                carriedRaw |= rung.raws[i];
                if (isNear(i)) keep(i);
            }
        }

        const length = coordinates.length;
        return {
            coordinates,
            times: times.slice(0, length),
            gaps: gaps.slice(0, length),
            raws: raws.slice(0, length),
            breaks: breaks.slice(0, length),
            sources: sources.slice(0, length),
            recorded: points.length,
        };
    }

    return {
        get recorded() {
            return points.length;
        },
        drawnFor(view) {
            if (points.length === 0) return EMPTY_TRACK;

            const budget = view.budget ?? DISPLAY_BUDGET;

            // Start at the rung this zoom deserves. Anything else would hold on to a
            // coarse line while the reader zooms in, which is the whole thing this is
            // here to avoid.
            const finest = stepFor(view.tolerance);
            let step = finest;

            // A view can still ask for more than the map should be handed: a whole long
            // track at a zoom that would show every metre of it. Then a coarser rung
            // answers instead, and how much coarser is worked out from the boxes rather
            // than by clipping every candidate on the way — each rung is roughly a
            // quarter of the one below it. Zooming in narrows the view, which gives the
            // detail back.
            for (let attempt = 0; attempt < MAX_RUNGS; attempt++) {
                const estimate = reach(rungAt(step), view.bounds);
                if (estimate <= budget) break;
                step += Math.max(1, Math.round(Math.log2(estimate / budget) / 2));
            }

            const track = clip(rungAt(step), view.bounds);

            // The estimate reads whole chunks, so it can send the search a rung too far
            // up. One look back is enough to take that back: at most one more clip, and
            // only when there is clearly room for the finer line.
            if (step > finest && track.coordinates.length * 4 <= budget) {
                const finer = clip(rungAt(step - 1), view.bounds);
                if (finer.coordinates.length <= budget) return finer;
            }
            return track;
        },
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
