/**
 * How much ground a bundle actually covers.
 *
 * Bundling is a screen-space question (see pin_bundling), so zoomed far enough out
 * a pill can stand for devices that are nowhere near each other. Past
 * {@link BUNDLE_SPREAD_MIN_DISTANCE} that is worth saying out loud: the map draws
 * the circle the members lie in, and the pill points at the top of it.
 */

export interface LngLat {
    lng: number;
    lat: number;
}

/** The circle a bundle's members lie in. */
export interface BundleSpread {
    center: LngLat;
    /** In metres. Every member of the bundle lies inside. */
    radius: number;
    /** Due north of the centre, on the circle — where the bundle's pill is anchored. */
    top: LngLat;
}

/** Below this the members are close enough that a circle would say nothing. */
export const BUNDLE_SPREAD_MIN_DISTANCE = 10_000;

/** Mean earth radius, in metres (IUGG). */
const EARTH_RADIUS = 6_371_008.8;

function toRadians(degrees: number): number {
    return (degrees * Math.PI) / 180;
}

function toDegrees(radians: number): number {
    return (radians * 180) / Math.PI;
}

/** Longitude folded back into [-180, 180]. */
function normalizeLongitude(degrees: number): number {
    return ((degrees + 540) % 360) - 180;
}

/** Great-circle distance in metres (haversine). */
function distanceBetween(a: LngLat, b: LngLat): number {
    const latA = toRadians(a.lat);
    const latB = toRadians(b.lat);
    const dLat = latB - latA;
    const dLng = toRadians(b.lng - a.lng);

    const h =
        Math.sin(dLat / 2) ** 2 + Math.cos(latA) * Math.cos(latB) * Math.sin(dLng / 2) ** 2;
    return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The point halfway along the great circle between [a] and [b]. */
function midpointBetween(a: LngLat, b: LngLat): LngLat {
    const latA = toRadians(a.lat);
    const latB = toRadians(b.lat);
    const lngA = toRadians(a.lng);
    const dLng = toRadians(b.lng - a.lng);

    const x = Math.cos(latB) * Math.cos(dLng);
    const y = Math.cos(latB) * Math.sin(dLng);

    return {
        lat: toDegrees(
            Math.atan2(
                Math.sin(latA) + Math.sin(latB),
                Math.sqrt((Math.cos(latA) + x) ** 2 + y ** 2),
            ),
        ),
        lng: normalizeLongitude(toDegrees(lngA + Math.atan2(y, Math.cos(latA) + x))),
    };
}

/** The point [distance] metres from [from] along [bearing] (in radians, 0 = north). */
function destination(from: LngLat, bearing: number, distance: number): LngLat {
    const angular = distance / EARTH_RADIUS;
    const lat = toRadians(from.lat);
    const lng = toRadians(from.lng);

    const targetLat = Math.asin(
        Math.sin(lat) * Math.cos(angular) + Math.cos(lat) * Math.sin(angular) * Math.cos(bearing),
    );
    const targetLng =
        lng +
        Math.atan2(
            Math.sin(bearing) * Math.sin(angular) * Math.cos(lat),
            Math.cos(angular) - Math.sin(lat) * Math.sin(targetLat),
        );

    return {lat: toDegrees(targetLat), lng: normalizeLongitude(toDegrees(targetLng))};
}

/**
 * The circle [points] lie in, or `null` when they sit closer together than
 * {@link BUNDLE_SPREAD_MIN_DISTANCE} and there is nothing to point out.
 *
 * The two members furthest apart set it: they are what "how far apart is this
 * bundle" means, and the diameter it takes to hold both.
 */
export function bundleSpread(points: LngLat[]): BundleSpread | null {
    if (points.length < 2) return null;

    let widest = 0;
    let from = points[0];
    let to = points[0];
    // O(n²) over the members of one bundle — a handful at most.
    for (let a = 0; a < points.length; a++) {
        for (let b = a + 1; b < points.length; b++) {
            const distance = distanceBetween(points[a], points[b]);
            if (distance <= widest) continue;
            widest = distance;
            from = points[a];
            to = points[b];
        }
    }

    if (widest <= BUNDLE_SPREAD_MIN_DISTANCE) return null;

    // Centred between the two, then widened until nothing sticks out: from three
    // members on, the widest pair alone need not hold the rest.
    const center = midpointBetween(from, to);
    let radius = widest / 2;
    for (const point of points) radius = Math.max(radius, distanceBetween(center, point));

    return {center, radius, top: destination(center, 0, radius)};
}

/**
 * The circle as a closed ring of coordinates, [steps] points around the centre.
 * Drawn in real distances rather than in pixels, so it keeps covering the same
 * ground at every zoom.
 *
 * [scale] draws it smaller than it is, which is what the grow-in animation rides on.
 *
 * The step count is what keeps the outline round rather than visibly many-sided,
 * and a circle costs a few dozen points — cheap next to a location history.
 */
export function spreadRing(spread: BundleSpread, scale = 1, steps = 128): number[][] {
    const radius = spread.radius * scale;
    const ring: number[][] = [];
    for (let step = 0; step <= steps; step++) {
        const point = destination(spread.center, (step / steps) * 2 * Math.PI, radius);
        ring.push([point.lng, point.lat]);
    }
    return ring;
}
