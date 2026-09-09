<script lang="ts">
    import { onMount, mount, unmount } from "svelte";
    import { MediaQuery } from "svelte/reactivity";
    import mapboxgl from "mapbox-gl";
    import "mapbox-gl/dist/mapbox-gl.css";
    import { getMapboxToken } from "$lib/api/mapbox/get_mapbox_token";
    import {webappSocket, shareMainText, isReconnecting} from "$lib/state/webapp_socket.svelte";
    import { foreignShares, shareOriginBase } from "$lib/state/share_socket.svelte";
    import { mapCamera, releaseCameraToUser } from "$lib/state/map_camera.svelte";
    import { mapTrail } from "$lib/state/map_trail.svelte";
    import {
        coordinateAt,
        createTrailSource,
        EMPTY_TRACK,
        positionAtTime,
        recordedAt,
        toleranceFor,
        type DisplayTrack,
        type TrackPosition,
        type TrailSource,
        type TrailView
    } from "./trail_display";
    import TrailPointPopover from "./TrailPointPopover.svelte";
    import {
        bandFlags,
        trailBandColors,
        trailData,
        type TrailBand,
        type TrailFocus
    } from "./trail_features";
    import type { HistoryPoint } from "$lib/api/history/history_repository";
    import {cubicOut} from "svelte/easing";
    import MapPin from "./MapPin.svelte";
    import MapBundle from "./MapBundle.svelte";
    import {bundleSpread, spreadRing, type BundleSpread} from "./bundle_spread";
    import {
        bundleOverlappingPins,
        PIN_HEIGHT,
        PIN_WIDTH,
        type PinBundle,
    } from "./pin_bundling";
    import mapDark from "$lib/assets/map-dark.png";
    import mapLight from "$lib/assets/map-light.png";

    let mapContainer: HTMLDivElement | null = $state(null);
    let showPlaceholder = $state(false);
    let map: mapboxgl.Map | undefined = $state();

    // One mapbox marker + mounted component per drawn pin, keyed by what it draws
    // (see pinKey / bundleKey) — a lone device or share, or a bundle of them.
    type PinEntry = { marker: mapboxgl.Marker; component: Record<string, any> };
    const pins = new Map<string, PinEntry>();

    /**
     * Takes a drawn pin off the map, playing its shrink-back-in outro first (see
     * pinPop) — pins and their bundle trade places at the same spot and in the same
     * instant, and cutting either of them would read as a flicker. The marker is
     * only dropped once the outro is through, so the outgoing pin stays put while
     * it plays.
     *
     * [animated] is off for the teardown, where the whole map is going anyway.
     */
    function removePin(key: string, animated = true) {
        const entry = pins.get(key);
        if (entry == null) return;
        pins.delete(key);

        if (!animated) {
            entry.marker.remove();
            void unmount(entry.component);
            return;
        }

        void unmount(entry.component, {outro: true}).then(() => entry.marker.remove());
    }

    const darkMode = new MediaQuery("(prefers-color-scheme: dark)");
    const style = $derived(
        darkMode.current
            ? "mapbox://styles/mapbox/traffic-night-v2"
            : "mapbox://styles/mapbox/standard"
    );

    // The location-history line: a light casing under a solid stroke, so the
    // trail stays legible over both map styles. The colours mirror the theme's
    // `--primary` / `--background` (layout.css) as hex, because mapbox-gl cannot
    // parse the oklch() values those tokens are written in.
    const TRAIL_SOURCE = "location-history";
    const TRAIL_CASING_LAYER = "location-history-casing";
    const TRAIL_LINE_LAYER = "location-history-line";
    const TRAIL_GAP_LAYER = "location-history-gap";
    const TRAIL_FOCUS_LINE_LAYER = "location-history-focus-line";
    const TRAIL_FOCUS_GAP_LAYER = "location-history-focus-gap";
    const TRAIL_POINT_SOURCE = "location-history-points";
    const TRAIL_POINT_LAYER = "location-history-points-hover";
    const TRAIL_PUCK_SOURCE = "location-history-puck";
    const TRAIL_PUCK_LAYER = "location-history-puck-dot";
    const TRAIL_BAND_COLORS = $derived(trailBandColors(darkMode.current));

    const trailColors = $derived(
        darkMode.current
            ? { primary: "#e2e8f0", casing: "#020617", outline: "rgba(226,232,240,0.5)" }
            : { primary: "#0f172a", casing: "#ffffff", outline: "rgba(15,23,42,0.5)" }
    );

    /**
     * Stretches the optimizer has not reached yet are drawn violet: they are raw
     * measurements, still carrying the jitter and the standstill clouds that the
     * optimized part has had removed. Own hex values for the same reason as
     * {@link trailColors} — mapbox-gl cannot read the theme's oklch() tokens.
     */
    const trailRawColor = $derived(darkMode.current ? "#a78bfa" : "#7c3aed");

    /**
     * How the line answers the timeline: what the window shows is white, what lies
     * before it recedes into the map as dark grey, what lies after it stays light but
     * quiet, and a marked range is the one stretch that carries colour.
     *
     * The highlight is a colour of its own rather than the theme's `--primary`: that
     * token is near-black in light mode and near-white in dark, and neither would be
     * seen on a map. It is the same amber the timeline marks a range in.
     */
    // Counts style loads: the initial one and each dark-mode swap. A style change
    // drops custom sources and layers, so the trail effect depends on this to
    // know when to (re)add them. A counter rather than a boolean, so a *second*
    // load is also a change the effect can see.
    let styleEpoch = $state(0);

    // Counts camera changes. Whether two pins cover each other is decided in screen
    // space, so panning, zooming, rotating or tilting alone can bundle them or pull
    // them apart again — this is what tells the pin effect to look anew.
    let cameraEpoch = $state(0);

    /**
     * Counts the camera coming to rest. Which detail the trail is drawn at follows the
     * zoom, and what of it is drawn follows the viewport — both settle at `moveend`,
     * and redoing the choice mid-gesture would cost more than it shows.
     */
    let trailViewEpoch = $state(0);

    /** How far past the edge of the screen the trail is still drawn, as a share of it. */
    const VIEW_PADDING = 0.25;

    /** Keeps the trail below the style's labels so road/place names stay readable. */
    function firstSymbolLayerId(currentMap: mapboxgl.Map): string | undefined {
        return currentMap.getStyle()?.layers?.find((layer) => layer.type === "symbol")?.id;
    }

    /**
     * Adds the trail's source and layers if they aren't there yet. Returns whether
     * the map is ready to be drawn on: adding throws while a style swap is
     * mid-flight, and the `style.load` that follows bumps `styleEpoch` and re-runs
     * the caller, so skipping a beat here is safe.
     */
    function addTrailLayers(currentMap: mapboxgl.Map): boolean {
        if (currentMap.getSource(TRAIL_SOURCE) != null) return true;

        try {
            currentMap.addSource(TRAIL_SOURCE, { type: "geojson", data: trailData([], [], [], []) });

            // The band decides the colour; only inside the window does the track's own
            // state still show through, violet where it is raw. One expression, so a
            // stretch cannot end up in two of them.
            const lineColor: mapboxgl.ExpressionSpecification = [
                "match",
                ["get", "band"],
                "before", TRAIL_BAND_COLORS.before,
                "after", TRAIL_BAND_COLORS.after,
                "selected", TRAIL_BAND_COLORS.selected,
                ["case", ["get", "raw"], trailRawColor, TRAIL_BAND_COLORS.window]
            ];

            /*
             * What is drawn under the line. White on a light basemap is barely there,
             * so the outline is what gives it an edge to be read against — at full
             * strength for the stretch on show, at half for the ones that have stepped
             * back, which is what keeps them legible without pulling the eye. The
             * marked range keeps the neutral casing: it carries its own colour and a
             * second one around it would only compete with it.
             */
            const casingColor: mapboxgl.ExpressionSpecification = [
                "match",
                ["get", "band"],
                "window", trailColors.primary,
                "selected", trailColors.casing,
                trailColors.outline
            ];

            // Which stretches are on show, and which have stepped back. A trail crosses
            // itself, so the two are drawn in two passes: the dimmed ones first, the
            // highlighted ones last and therefore on top, where they cannot be painted
            // over by a stretch that was meant to recede.
            const FOCUS_BANDS = ["window", "selected"];
            const DIMMED_BANDS = ["before", "after"];
            const inBands = (bands: string[]): mapboxgl.ExpressionSpecification =>
                ["match", ["get", "band"], bands, true, false];
            const solid = (bands: string[]): mapboxgl.ExpressionSpecification =>
                ["all", ["!", ["get", "gap"]], inBands(bands)];
            const dotted = (bands: string[]): mapboxgl.ExpressionSpecification =>
                ["all", ["get", "gap"], inBands(bands)];

            // Recording gaps are drawn as dots: round caps plus a zero-length dash.
            // Dash lengths are multiples of the line width, so 2 = one dot diameter of
            // spacing. `line-dasharray` takes no data-driven expression, hence a layer
            // of its own rather than a filter on the solid one.
            const line = (id: string, filter: mapboxgl.ExpressionSpecification, gap: boolean): mapboxgl.LayerSpecification => ({
                id,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                filter,
                layout: { "line-cap": "round", "line-join": "round" },
                paint: {
                    "line-color": lineColor,
                    "line-width": 3.5,
                    "line-opacity": gap ? 0.45 : 0.9,
                    ...(gap ? { "line-dasharray": [0, 2] as [number, number] } : {})
                }
            });

            // `slot` positions the layers in the v3 "standard" style (which imports
            // its basemap, so it exposes no symbol layers to sort against); the
            // beforeId does the same job in the classic night style. Inserting several
            // layers before the same one stacks them in insertion order.
            const beforeId = firstSymbolLayerId(currentMap);
            // Solid stretches only — a solid casing under the dots would undo the
            // point of drawing them faintly.
            currentMap.addLayer({
                id: TRAIL_CASING_LAYER,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                // Only under what is on show: the casing is there to hold a bright line
                // off the map, and drawing it under the dimmed stretches would give them
                // back the weight they were just relieved of.
                filter: ["!", ["get", "gap"]],
                layout: { "line-cap": "round", "line-join": "round" },
                paint: { "line-color": casingColor, "line-width": 7, "line-opacity": 0.7 }
            }, beforeId);

            currentMap.addLayer(line(TRAIL_LINE_LAYER, solid(DIMMED_BANDS), false), beforeId);
            currentMap.addLayer(line(TRAIL_GAP_LAYER, dotted(DIMMED_BANDS), true), beforeId);
            currentMap.addLayer(line(TRAIL_FOCUS_LINE_LAYER, solid(FOCUS_BANDS), false), beforeId);
            currentMap.addLayer(line(TRAIL_FOCUS_GAP_LAYER, dotted(FOCUS_BANDS), true), beforeId);

            /*
             * The drawn positions as invisible features, so hovering can report which
             * one the cursor is near: the trail is drawn as lines, and a line cannot say
             * *where* along itself it was touched. Its own source, because the line's
             * data is rewritten on every frame of the grow-in animation and rebuilding
             * these at that rate would stall the map.
             *
             * The radius is deliberately tiny — what counts as near is decided by
             * measuring against the cursor (see TRAIL_HOVER_RADIUS), not by how big
             * these are.
             */
            currentMap.addSource(TRAIL_POINT_SOURCE, {type: "geojson", data: trailPointData(EMPTY_TRACK)});
            currentMap.addLayer({
                id: TRAIL_POINT_LAYER,
                type: "circle",
                slot: "middle",
                source: TRAIL_POINT_SOURCE,
                paint: {"circle-radius": 1, "circle-opacity": 0, "circle-stroke-width": 0}
            }, beforeId);

            // The puck itself, added last so it sits on top of the line it marks.
            currentMap.addSource(TRAIL_PUCK_SOURCE, {type: "geojson", data: trailPuckData(null)});
            currentMap.addLayer({
                id: TRAIL_PUCK_LAYER,
                type: "circle",
                slot: "middle",
                source: TRAIL_PUCK_SOURCE,
                paint: {
                    "circle-radius": 7,
                    "circle-color": trailColors.primary,
                    "circle-stroke-width": 3,
                    "circle-stroke-color": trailColors.casing
                }
            }, beforeId);
            return true;
        } catch {
            return false;
        }
    }

    function setTrailCoordinates(
        currentMap: mapboxgl.Map,
        coordinates: number[][],
        gaps: ArrayLike<number | boolean> = [],
        raws: ArrayLike<number | boolean> = [],
        bands: TrailBand[] = [],
        breaks: ArrayLike<number | boolean> = []
    ) {
        const source = currentMap.getSource(TRAIL_SOURCE);
        if (source?.type === "geojson") source.setData(trailData(coordinates, gaps, raws, bands, breaks));
    }

    const TRAIL_ANIMATION_MS = 2000;

    /**
     * Heavy ease-out: the line shoots ahead immediately and spends most of the
     * two seconds settling into its final shape.
     */
    function easeOutExpo(t: number): number {
        return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    /**
     * Cumulative length along the trail. Longitude is scaled by the latitude's
     * cosine so a degree of lng counts for what it's actually worth on screen —
     * otherwise the tip would race through east-west stretches.
     */
    function cumulativeLengths(coordinates: number[][]): number[] {
        const lengths = [0];
        for (let i = 1; i < coordinates.length; i++) {
            const [previousLng, previousLat] = coordinates[i - 1];
            const [lng, lat] = coordinates[i];
            const scale = Math.cos(((previousLat + lat) / 2) * (Math.PI / 180));
            const dx = (lng - previousLng) * scale;
            const dy = lat - previousLat;
            lengths.push(lengths[i - 1] + Math.hypot(dx, dy));
        }
        return lengths;
    }

    /**
     * The trail truncated to `progress` (0–1) of its total length, interpolating
     * within the final segment so the tip glides instead of hopping vertex to
     * vertex.
     */
    function trailUpTo(coordinates: number[][], lengths: number[], progress: number): number[][] {
        const total = lengths[lengths.length - 1];
        if (progress >= 1 || total === 0) return coordinates;

        const target = total * progress;
        // The last vertex at or before the target — everything up to it is kept.
        let index = 0;
        while (index + 1 < lengths.length && lengths[index + 1] <= target) index++;

        const head = coordinates.slice(0, index + 1);
        const next = coordinates[index + 1];
        if (next == null) return head;

        const segment = lengths[index + 1] - lengths[index];
        const fraction = segment === 0 ? 0 : (target - lengths[index]) / segment;
        const [lng, lat] = coordinates[index];
        head.push([lng + (next[0] - lng) * fraction, lat + (next[1] - lat) * fraction]);
        return head;
    }

    const reducedMotion = new MediaQuery("(prefers-reduced-motion: reduce)");
    let trailFrame: number | null = null;

    /**
     * When the running grow-in animation started, or null when none is running. A trail
     * that grows while it is being drawn — the cache first, the server's answer a moment
     * later — continues from this instant instead of starting over.
     */
    let trailAnimationStart: number | null = null;

    function cancelTrailAnimation() {
        if (trailFrame != null) cancelAnimationFrame(trailFrame);
        trailFrame = null;
    }


    /*
     * ── The hover puck ──────────────────────────────────────────────────────────
     * A trail is a line, and a line cannot say where along itself it was touched.
     * The drawn positions are published a second time as invisible point features so
     * that mapbox's own spatial index can answer which of them the cursor is near;
     * the answer is then measured properly, against the segments between them.
     */

    /** The drawn positions as their own features, carrying only their place in the track. */
    type TrailPointFeature = {
        type: "Feature";
        properties: {index: number};
        geometry: {type: "Point"; coordinates: number[]};
    };
    type TrailPointData = {type: "FeatureCollection"; features: TrailPointFeature[]};

    function trailPointData(track: DisplayTrack): TrailPointData {
        return {
            type: "FeatureCollection",
            features: track.coordinates.map((coordinates, index) => ({
                type: "Feature",
                properties: {index},
                geometry: {type: "Point", coordinates}
            }))
        };
    }

    function trailPuckData(coordinates: [number, number] | null) {
        return {
            type: "FeatureCollection" as const,
            features: coordinates == null
                ? []
                : [{
                    type: "Feature" as const,
                    properties: {},
                    geometry: {type: "Point" as const, coordinates}
                }]
        };
    }

    /**
     * How close to the *trail* the cursor has to be to count as hovering it, in screen
     * pixels. It applies to the segments between the positions exactly as it does to
     * the positions themselves — the line is what is visible, so that is what is aimed
     * at.
     */
    const TRAIL_HOVER_RADIUS = 6;

    /**
     * How far to look for candidates when the cursor is near none of the positions
     * themselves. A long straight stretch has its ends far apart, and the cursor can
     * sit on the line while being hundreds of pixels from either of them.
     */
    const TRAIL_HOVER_SEGMENT_SEARCH = 250;

    /** Where the cursor put the puck, and where the timeline did. The timeline wins. */
    let pointerHover: TrackPosition | null = $state(null);

    /** What the popover above the puck reads, mutated rather than replaced (see its props). */
    const puckState = $state<{point: HistoryPoint | null}>({point: null});

    let puckMarker: mapboxgl.Marker | null = null;
    let puckPopover: Record<string, any> | null = null;

    /**
     * Where on the segment [from]→[to] the cursor sits: how far along it (0–1, clamped
     * to the segment) and how far off it, both in screen pixels.
     */
    function nearestOnSegment(from: mapboxgl.Point, to: mapboxgl.Point, cursor: mapboxgl.Point) {
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const lengthSquared = dx * dx + dy * dy;
        // Two positions on the same pixel are their own start rather than a division by 0.
        const fraction = lengthSquared === 0
            ? 0
            : Math.min(1, Math.max(0, ((cursor.x - from.x) * dx + (cursor.y - from.y) * dy) / lengthSquared));

        return {
            fraction,
            distance: Math.hypot(from.x + dx * fraction - cursor.x, from.y + dy * fraction - cursor.y)
        };
    }

    /** Follows the cursor along the trail, or lets go once it is too far from the line. */
    function updateHover(event: mapboxgl.MapMouseEvent) {
        const currentMap = map;
        // Bound to the map rather than to the layer, so it also fires beside the trail.
        // The layer is gone between style swaps and while no trail is shown.
        if (currentMap == null || currentMap.getLayer(TRAIL_POINT_LAYER) == null) return;

        const coordinates = drawn.coordinates;
        const cursor = event.point;

        // A box is what the query takes. Asking mapbox rather than walking the track
        // also keeps positions on the far side of the globe out of it: they are not
        // rendered, so they are not returned.
        const positionsWithin = (radius: number) => currentMap.queryRenderedFeatures(
            [[cursor.x - radius, cursor.y - radius], [cursor.x + radius, cursor.y + radius]],
            {layers: [TRAIL_POINT_LAYER]}
        ) as unknown as TrailPointFeature[];

        // Close by first; only if the cursor is near no position at all is it worth
        // looking for the far-apart ends of a long segment.
        const candidates = positionsWithin(TRAIL_HOVER_RADIUS);
        const searched = candidates.length > 0 ? candidates : positionsWithin(TRAIL_HOVER_SEGMENT_SEARCH);

        // Neighbouring candidates share endpoints, so each position is projected once.
        const projected = new Map<number, mapboxgl.Point>();
        const project = (index: number) => {
            let point = projected.get(index);
            if (point == null) {
                point = currentMap.project(coordinates[index] as [number, number]);
                projected.set(index, point);
            }
            return point;
        };

        // In a container, because TypeScript does not follow assignments made inside the
        // closure below and would otherwise take the result for `null` here.
        const nearest: {found: TrackPosition & {distance: number} | null} = {found: null};
        const consider = (index: number) => {
            if (coordinates[index] == null) return;

            const from = project(index);
            const {fraction, distance} = coordinates[index + 1] == null
                ? {fraction: 0, distance: Math.hypot(from.x - cursor.x, from.y - cursor.y)}
                : nearestOnSegment(from, project(index + 1), cursor);

            if (distance > TRAIL_HOVER_RADIUS) return;
            if (nearest.found == null || distance < nearest.found.distance) {
                nearest.found = {index, fraction, distance};
            }
        };

        for (const candidate of searched) {
            const index = candidate.properties.index;
            // The track may have been replaced since the query — an index is only an index.
            if (coordinates[index] == null) continue;

            consider(index - 1);
            consider(index);
        }

        pointerHover = nearest.found == null
            ? null
            : {index: nearest.found.index, fraction: nearest.found.fraction};
    }

    /** Puts the puck where the timeline points, or else where the cursor does. */
    function showPuck(currentMap: mapboxgl.Map, position: TrackPosition | null) {
        const coordinates = position == null ? null : coordinateAt(drawn, position);

        const source = currentMap.getSource(TRAIL_PUCK_SOURCE);
        if (source?.type === "geojson") source.setData(trailPuckData(coordinates));

        puckState.point = position == null ? null : recordedAt(drawn, sourceFrom ?? [], position);

        if (coordinates == null) return;

        if (puckMarker == null) {
            const element = document.createElement("div");
            puckPopover = mount(TrailPointPopover, {target: element, props: {state: puckState}});
            // Anchored above the puck, clear of the line it marks. It renders nothing
            // while there is no point, so the marker can simply stay put.
            puckMarker = new mapboxgl.Marker({element, anchor: "bottom", offset: [0, -14]})
                .setLngLat(coordinates)
                .addTo(currentMap);
            return;
        }
        puckMarker.setLngLat(coordinates);
    }

    /**
     * A redraw waiting for the next frame. One wheel gesture publishes several windows
     * per frame, and each of them would otherwise cost a rebuild and a hand-off to
     * mapbox — of which only the last is ever seen.
     */
    let pendingDraw: (() => void) | null = null;
    let drawFrame: number | null = null;

    function scheduleDraw(draw: () => void) {
        pendingDraw = draw;
        if (drawFrame != null) return;

        drawFrame = requestAnimationFrame(() => {
            drawFrame = null;
            const run = pendingDraw;
            pendingDraw = null;
            run?.();
        });
    }

    function cancelScheduledDraw() {
        if (drawFrame != null) cancelAnimationFrame(drawFrame);
        drawFrame = null;
        pendingDraw = null;
    }

    /** Both of the above: nothing left running, nothing left waiting. */
    function stopTrailDrawing() {
        cancelTrailAnimation();
        cancelScheduledDraw();
    }

    /**
     * Grows the trail in from its oldest point over {@link TRAIL_ANIMATION_MS}, counted
     * from [animateFrom]. Passing the start of an animation that is already running
     * carries it on with the new geometry; null draws the finished line at once.
     */
    /**
     * The history a view is answered from, and the stretch last chosen out of it. A
     * recolour must not choose again — the timeline moves at sixty frames a second
     * while neither the history nor the camera has moved at all.
     */
    let trailSource: TrailSource | null = null;
    let sourceFrom: HistoryPoint[] | null = null;
    let drawn: DisplayTrack = EMPTY_TRACK;

    /** Which view the drawn stretch was chosen for, so an unchanged one is not redone. */
    let drawnFor: string | null = null;

    /**
     * What the map is looking at. The bounds are padded, so panning a little does not
     * run past the end of the line, and so a stretch entering the view comes in from
     * off screen rather than starting at its edge.
     */
    function viewOf(currentMap: mapboxgl.Map): TrailView {
        const bounds = currentMap.getBounds();
        if (bounds == null) return {tolerance: 0, bounds: null};

        const west = bounds.getWest();
        const east = bounds.getEast();
        const south = bounds.getSouth();
        const north = bounds.getNorth();
        const padLng = (east - west) * VIEW_PADDING;
        const padLat = (north - south) * VIEW_PADDING;

        return {
            tolerance: toleranceFor(currentMap.getZoom(), currentMap.getCenter().lat),
            bounds: [west - padLng, south - padLat, east + padLng, north + padLat]
        };
    }

    /**
     * The stretch to draw: as much detail as this zoom can show, over what is on
     * screen. Recomputed when the history changes or the camera has come to rest, and
     * reused for everything else — moving the timeline recolours the same geometry.
     */
    function trackOf(currentMap: mapboxgl.Map, points: HistoryPoint[]): DisplayTrack {
        if (points !== sourceFrom) {
            trailSource = createTrailSource(points);
            sourceFrom = points;
            drawnFor = null;
        }

        const view = viewOf(currentMap);
        const signature = `${view.tolerance.toExponential(3)}|${view.bounds?.map((v) => v.toFixed(5)).join(",")}`;
        if (signature === drawnFor) return drawn;

        drawn = trailSource?.drawnFor(view) ?? EMPTY_TRACK;
        drawnFor = signature;

        // The positions the hover is measured against are the ones on screen, published
        // whenever that changes rather than per frame: the line's own data is rewritten
        // while it grows in, and rebuilding thousands of point features at that rate
        // would stall the map.
        const source = currentMap.getSource(TRAIL_POINT_SOURCE);
        if (source?.type === "geojson") source.setData(trailPointData(drawn));

        // Whatever the puck was standing on belonged to the stretch just replaced.
        pointerHover = null;
        return drawn;
    }

    function drawTrail(
        currentMap: mapboxgl.Map,
        points: HistoryPoint[],
        animateFrom: number | null,
        focus: TrailFocus
    ) {
        cancelTrailAnimation();

        const {coordinates, times, gaps, raws, breaks} = trackOf(currentMap, points);
        const bands = bandFlags(times, focus);
        if (animateFrom == null || coordinates.length < 2) {
            trailAnimationStart = null;
            setTrailCoordinates(currentMap, coordinates, gaps, raws, bands, breaks);
            return;
        }

        trailAnimationStart = animateFrom;
        const lengths = cumulativeLengths(coordinates);
        // The truncated head keeps the original point indices (its interpolated tip sits
        // in the segment it replaces), so `gaps` still lines up.
        const drawUpTo = (now: number) => {
            const t = Math.min(1, (now - animateFrom) / TRAIL_ANIMATION_MS);
            setTrailCoordinates(currentMap, trailUpTo(coordinates, lengths, easeOutExpo(t)), gaps, raws, bands, breaks);
            return t;
        };
        const step = (now: number) => {
            if (drawUpTo(now) < 1) {
                trailFrame = requestAnimationFrame(step);
                return;
            }
            trailFrame = null;
            trailAnimationStart = null;
        };

        // The first frame is drawn straight away rather than clearing the line: at a
        // fresh start that is nothing yet, and a trail picking an animation back up is
        // already part-way in and must not flash empty.
        drawUpTo(performance.now());
        trailFrame = requestAnimationFrame(step);
    }

    onMount(() => {
        getMapboxToken().then((accessToken) => {
            if (accessToken == null) {
                showPlaceholder = true;
                return;
            }

            map = new mapboxgl.Map({
                accessToken,
                container: mapContainer!,
                style,
                projection: "globe",
                center: [13.7373, 51.0504],
                zoom: 11
            });

            // Any hands-on camera interaction switches the *currently driving*
            // scope to manual. Programmatic camera moves (our own fitBounds/flyTo)
            // have no `originalEvent`, so they don't trip this.
            const onUserInteraction = (e: { originalEvent?: unknown }) => {
                if (e.originalEvent != null) releaseCameraToUser();
            };
            map.on("dragstart", onUserInteraction);
            map.on("zoomstart", onUserInteraction);
            map.on("rotatestart", onUserInteraction);
            map.on("pitchstart", onUserInteraction);

            // Fires for the initial style and again after every setStyle.
            map.on("style.load", () => styleEpoch++);

            // Fires for every camera change, including each frame of an animated one,
            // so the bundling keeps up with a flyTo instead of snapping at its end.
            map.on("move", () => cameraEpoch++);
            map.on("moveend", () => trailViewEpoch++);

            // Following the cursor along the trail. `mouseout` is what lets go when the
            // pointer leaves the map altogether rather than merely the line.
            map.on("mousemove", updateHover);
            map.on("mouseout", () => (pointerHover = null));
        });

        return () => {
            cancelTrailAnimation();
            cancelSpreadAnimation();
            for (const key of [...pins.keys()]) removePin(key, false);
            if (puckPopover != null) void unmount(puckPopover);
            puckMarker?.remove();
            puckMarker = null;
            puckPopover = null;
            map?.remove();
        };
    });

    $effect(() => {
        map?.setStyle(style);
    });

    /**
     * The puck stands wherever something is pointing: the timeline first — a reader
     * moving along it is asking about a moment, and the map answers with a place — and
     * the cursor on the map otherwise.
     */
    $effect(() => {
        const currentMap = map;
        const at = mapTrail.hoveredAt;
        const pointer = pointerHover;
        if (currentMap == null || currentMap.getLayer(TRAIL_PUCK_LAYER) == null) return;

        showPuck(currentMap, at != null ? positionAtTime(drawn, at) : pointer);
    });

    // The track the grow-in animation last played for, so everything that re-runs the
    // effect for the *same* track — a style swap, a history that arrived in pieces —
    // restores or continues the line instead of replaying it.
    let animatedTrailKey: string | null = null;

    // Draw the published location history (see setMapTrail). Clearing it on
    // teardown is what removes the line when the detail view navigates away —
    // and the effect re-runs after a style swap wiped the layers.
    $effect(() => {
        // All three dependencies are read into locals up front, before any early
        // return, so the effect re-runs no matter which of them settles last.
        // `styleEpoch` in particular must be a real read (not a bare reference):
        // on a direct page load the map needs a token fetch plus a style download,
        // so the history is usually published *first* and the draw below only
        // becomes possible once the style load bumps the epoch.
        const currentMap = map;
        const epoch = styleEpoch;
        // Read so that coming to rest at a new zoom or over new ground redraws the
        // line at the detail that view deserves.
        void trailViewEpoch;
        const points = mapTrail.points;
        const trailKey = mapTrail.key;
        // Read here so that moving the timeline re-runs this and recolours the line.
        // The key has not changed then, so it is redrawn where its animation left it
        // rather than growing in again.
        const focus = { window: mapTrail.window, selection: mapTrail.selection };

        // `style.load` (epoch > 0) is the signal that a style is in place, and
        // deliberately not isStyleLoaded() — that one also waits for every tile to
        // arrive, so on a direct page load it is still false when the style is
        // long ready, and nothing would ever retry.
        if (currentMap == null || epoch === 0) return;
        if (!addTrailLayers(currentMap)) return;

        /*
         * A newly opened track grows in. The same track re-published — its cached part
         * followed by whatever the server added, or a dark-mode style swap that wiped the
         * layers — carries on from where its animation is, and once that has finished
         * (or never ran) the line is simply put back complete.
         *
         * Leaving a detail view publishes no trail at all, which also forgets the track:
         * coming back to it is a new trail and animates again.
         */
        const isNewTrail = trailKey !== animatedTrailKey;

        /*
         * Only a trail that has something in it counts as drawn. A view that has just
         * switched track publishes an empty list while its history is still on its way,
         * and were that to claim the key, the points arriving a moment later would be
         * taken for an update and never animate at all.
         */
        if (points.length > 0 || trailKey == null) animatedTrailKey = trailKey;

        const animateFrom = trailKey == null
            ? null
            : isNewTrail
                ? (reducedMotion.current ? null : performance.now())
                : trailAnimationStart;

        scheduleDraw(() => drawTrail(currentMap, points, animateFrom, focus));

        // Only the drawing is stopped here. Clearing the line as well would blank it
        // on every re-run — and this effect re-runs whenever the timeline moves, which
        // read as a flicker. A trail that is really gone publishes an empty list (see
        // the trail claim's release), and that draws as nothing by itself.
        return stopTrailDrawing;
    });

    /*
     * The ground a bundle covers, drawn for the ones whose members are genuinely far
     * apart (see bundle_spread): a full border around a translucent body, in a blue
     * of its own so it doesn't read as part of the trail. Real distances, so the
     * circle keeps covering the same ground at every zoom.
     */
    const SPREAD_SOURCE = "bundle-spread";
    const SPREAD_FILL_LAYER = "bundle-spread-fill";
    const SPREAD_LINE_LAYER = "bundle-spread-line";
    const SPREAD_FILL_OPACITY = 0.18;
    const spreadColor = $derived(darkMode.current ? "#60a5fa" : "#2563eb");

    type SpreadFeature = {
        type: "Feature";
        // How far the circle is faded in; the layers read it per feature, so several
        // bundles can be at different points of their animation at the same time.
        properties: { opacity: number };
        geometry: { type: "Polygon"; coordinates: number[][][] };
    };
    type SpreadData = { type: "FeatureCollection"; features: SpreadFeature[] };

    /** Adds the circle's source and layers if they aren't there yet, see addTrailLayers. */
    function addSpreadLayers(currentMap: mapboxgl.Map): boolean {
        if (currentMap.getSource(SPREAD_SOURCE) != null) return true;

        try {
            currentMap.addSource(SPREAD_SOURCE, {
                type: "geojson",
                data: {type: "FeatureCollection", features: []}
            });

            const beforeId = firstSymbolLayerId(currentMap);
            currentMap.addLayer({
                id: SPREAD_FILL_LAYER,
                type: "fill",
                slot: "middle",
                source: SPREAD_SOURCE,
                paint: {
                    // Without this the fill draws its own soft edge right under the
                    // border, and the two together read as a blurred outline.
                    "fill-antialias": false,
                    "fill-color": spreadColor,
                    "fill-opacity": ["*", ["get", "opacity"], SPREAD_FILL_OPACITY]
                }
            }, beforeId);
            currentMap.addLayer({
                id: SPREAD_LINE_LAYER,
                type: "line",
                slot: "middle",
                source: SPREAD_SOURCE,
                layout: {"line-join": "round"},
                paint: {
                    "line-color": spreadColor,
                    "line-width": 2.5,
                    "line-opacity": ["get", "opacity"]
                }
            }, beforeId);
            return true;
        } catch {
            return false;
        }
    }

    /**
     * The circles currently on the map, keyed by the bundle they belong to.
     * [target] is where the circle is headed — 1 while its bundle is drawn, 0 once it
     * is gone; [progress] is where it has got to.
     */
    type SpreadState = { spread: BundleSpread; progress: number; target: 0 | 1 };
    const spreadStates = new Map<string, SpreadState>();

    // Same length and easing as the pins' grow-in (see pinPop), so a bundle and its
    // circle arrive together.
    const SPREAD_ANIMATION_MS = 220;

    let spreadFrame: number | null = null;
    let spreadFrameTime: number | null = null;

    function cancelSpreadAnimation() {
        if (spreadFrame != null) cancelAnimationFrame(spreadFrame);
        spreadFrame = null;
        spreadFrameTime = null;
    }

    function spreadData(): SpreadData {
        const features: SpreadFeature[] = [];
        for (const state of spreadStates.values()) {
            const shown = cubicOut(state.progress);
            if (shown <= 0) continue;
            features.push({
                type: "Feature",
                properties: {opacity: shown},
                // Grows out of its centre rather than fading in on the spot, so it
                // reads as the bundle taking up its ground.
                geometry: {type: "Polygon", coordinates: [spreadRing(state.spread, 0.6 + 0.4 * shown)]}
            });
        }
        return {type: "FeatureCollection", features};
    }

    function drawSpreads(currentMap: mapboxgl.Map) {
        const source = currentMap.getSource(SPREAD_SOURCE);
        if (source?.type === "geojson") source.setData(spreadData());
    }

    /** Moves every circle towards its target; returns whether any is still on its way. */
    function stepSpreads(now: number): boolean {
        const elapsed = spreadFrameTime == null ? 0 : now - spreadFrameTime;
        spreadFrameTime = now;
        const step = reducedMotion.current ? 1 : elapsed / SPREAD_ANIMATION_MS;

        let moving = false;
        for (const [key, state] of [...spreadStates]) {
            state.progress =
                state.target > state.progress
                    ? Math.min(state.target, state.progress + step)
                    : Math.max(state.target, state.progress - step);

            if (state.progress !== state.target) moving = true;
            else if (state.target === 0) spreadStates.delete(key);
        }
        return moving;
    }

    function animateSpreads(currentMap: mapboxgl.Map) {
        cancelSpreadAnimation();
        if (![...spreadStates.values()].some((state) => state.progress !== state.target)) return;

        spreadFrameTime = performance.now();
        const frame = (now: number) => {
            const moving = stepSpreads(now);
            drawSpreads(currentMap);
            if (!moving) {
                cancelSpreadAnimation();
                return;
            }
            spreadFrame = requestAnimationFrame(frame);
        };
        spreadFrame = requestAnimationFrame(frame);
    }

    // Bumped whenever a circle is added, dropped or has moved — the pin effect runs on
    // every camera frame, and only the rare run that changes something has to redraw.
    let spreadEpoch = $state(0);

    /** Hands the circles of the bundles that were just drawn over to the map. */
    function publishSpreads(spreads: Map<string, BundleSpread>) {
        let changed = false;

        for (const [key, spread] of spreads) {
            const state = spreadStates.get(key);
            if (state == null) {
                spreadStates.set(key, {spread, progress: 0, target: 1});
                changed = true;
                continue;
            }
            if (state.target === 0) changed = true;
            state.target = 1;
            // The members move, so the circle they lie in does too.
            if (state.spread.radius !== spread.radius || state.spread.center.lng !== spread.center.lng) {
                changed = true;
            }
            state.spread = spread;
        }

        for (const [key, state] of spreadStates) {
            if (spreads.has(key) || state.target === 0) continue;
            state.target = 0;
            changed = true;
        }

        if (changed) spreadEpoch++;
    }

    // Draw the circles, and keep them drawn across a style swap — that drops every
    // custom source and layer, which is what styleEpoch reports.
    $effect(() => {
        const currentMap = map;
        const epoch = styleEpoch;
        // A real read, like styleEpoch above: this is what a changed set of circles
        // reports, and it must not be optimised away.
        void spreadEpoch;
        if (currentMap == null || epoch === 0) return;
        if (!addSpreadLayers(currentMap)) return;

        drawSpreads(currentMap);
        animateSpreads(currentMap);

        return () => cancelSpreadAnimation();
    });

    /** One entity the map draws a pin for: an own device, or a share of either origin. */
    type PinTarget = {
        id: string;
        label: string;
        imageUrl: string;
        href: string;
        location: { longitude: number; latitude: number };
    };

    /** Every own device, same-server share and foreign share that has a location. */
    function pinTargets(): PinTarget[] {
        const targets: PinTarget[] = [];

        for (const device of webappSocket.devices) {
            const location = device.last_location;
            if (location == null) continue;
            targets.push({
                id: device.id,
                label: device.name,
                imageUrl: `/api/v1/devices/image/${device.manufacturer}-${device.model}`,
                href: `/devices/${device.id}`,
                location
            });
        }

        for (const share of webappSocket.shares) {
            const location = share.last_location;
            if (location == null) continue;
            targets.push({
                id: share.id,
                label: shareMainText(share),
                imageUrl: `/api/v1/devices/image/${share.manufacturer}-${share.model}`,
                href: `/share/${share.id}`,
                location
            });
        }

        for (const entry of foreignShares.entries) {
            const snapshot = entry.subscription.snapshot;
            const location = snapshot?.last_location;
            if (snapshot == null || location == null) continue;
            const base = shareOriginBase(entry.homeserver);
            targets.push({
                id: entry.activeShareId,
                label: shareMainText(snapshot),
                imageUrl: `${base}/api/v1/devices/image/${snapshot.manufacturer}-${snapshot.model}`,
                href: `/share/${entry.activeShareId}?homeserver=${encodeURIComponent(entry.homeserver)}`,
                location
            });
        }

        return targets;
    }

    // Add or move the marker for one drawn pin, kept under [key]: whatever the key
    // stands for is mounted once and only moved afterwards.
    function upsertPin(
        currentMap: mapboxgl.Map,
        key: string,
        lngLat: [number, number],
        makeComponent: (target: HTMLElement) => Record<string, any>
    ) {
        const existing = pins.get(key);
        if (existing != null) {
            existing.marker.setLngLat(lngLat);
            return;
        }

        const element = document.createElement("div");
        const component = makeComponent(element);
        const marker = new mapboxgl.Marker({ element, anchor: "bottom" })
            .setLngLat(lngLat)
            .addTo(currentMap);
        pins.set(key, { marker, component });
    }

    function drawPin(currentMap: mapboxgl.Map, target: PinTarget): string {
        const key = `pin:${target.id}`;
        upsertPin(currentMap, key, [target.location.longitude, target.location.latitude], (element) =>
            // `intro` so a pin leaving a bundle grows in rather than popping up.
            mount(MapPin, {
                target: element,
                intro: true,
                props: {
                    id: target.id,
                    label: target.label,
                    imageUrl: target.imageUrl,
                    href: target.href
                }
            })
        );
        return key;
    }

    function bundleKey(bundle: PinBundle<PinTarget>): string {
        // Keyed by its members, so a bundle that gains or loses one is a different
        // bundle and is drawn anew — which is also what keeps the mounted props right.
        return `bundle:${bundle.items.map((target) => target.id).sort().join("|")}`;
    }

    function drawBundle(
        currentMap: mapboxgl.Map,
        bundle: PinBundle<PinTarget>,
        spread: BundleSpread | null
    ): string {
        const key = bundleKey(bundle);
        // A bundle standing for ground rather than for a spot is anchored at the top of
        // its circle, so it points at what it covers instead of hiding the middle of it.
        // Without one, unprojecting the screen centre the bundling worked out (rather
        // than averaging coordinates) puts the pill exactly where it was measured.
        const anchor = spread?.top ?? currentMap.unproject([bundle.position.x, bundle.position.y]);
        upsertPin(currentMap, key, [anchor.lng, anchor.lat], (element) =>
            mount(MapBundle, { target: element, intro: true, props: { items: bundle.items } })
        );
        return key;
    }

    // Keep a pin on the map for every own device and share that has a location, with
    // the ones lying on top of each other drawn as a single bundle.
    $effect(() => {
        const currentMap = map;
        const targets = pinTargets();
        const opened = mapCamera.targetId;
        // A real read, not a bare reference: the bundling is worked out in screen
        // space, so it has to be redone whenever the camera moved.
        void cameraEpoch;

        if (currentMap == null) return;

        const seen = new Set<string>();

        // The opened device or share is never bundled away: it is what the camera
        // frames and what the map draws highlighted, so it keeps a pin of its own.
        const openedTarget = targets.find((target) => target.id === opened);
        if (openedTarget != null) seen.add(drawPin(currentMap, openedTarget));

        const bundles = bundleOverlappingPins(
            targets.filter((target) => target.id !== opened),
            (target) => currentMap.project([target.location.longitude, target.location.latitude])
        );
        const spreads = new Map<string, BundleSpread>();
        for (const bundle of bundles) {
            if (bundle.items.length === 1) {
                seen.add(drawPin(currentMap, bundle.items[0]));
                continue;
            }

            // Only bundles that stand for far-apart devices get a circle; the rest are
            // pins on the same spot, and drawing a ring around those says nothing.
            const spread = bundleSpread(
                bundle.items.map((target) => ({
                    lng: target.location.longitude,
                    lat: target.location.latitude
                }))
            );
            if (spread != null) spreads.set(bundleKey(bundle), spread);
            seen.add(drawBundle(currentMap, bundle, spread));
        }
        publishSpreads(spreads);

        // Drop what is no longer drawn: entities that vanished or lost their location,
        // and bundles whose members went their separate ways.
        for (const key of [...pins.keys()]) {
            if (!seen.has(key)) removePin(key);
        }
    });

    // Turn the card's bounding box into fitBounds padding insets (in pixels).
    // We reserve only the *single* edge the card is docked against, so the
    // remaining space stays a clean rectangle. fitBounds then centres the
    // devices inside it, giving an even margin (border) on all sides.
    //
    // fitBounds only fits the pins' anchor points, so we also add each pin's
    // overhang (top/sides) to the base margin to keep the whole pin visible.
    function cameraPadding(currentMap: mapboxgl.Map) {
        const gap = 16;
        const pinX = PIN_WIDTH / 2; // pin half-width around its anchor
        const pinTop = PIN_HEIGHT;  // pin height above its anchor
        const rect = mapCamera.contentRect;
        const overlay = mapCamera.overlayRect;

        const padding = {
            top: gap + pinTop,
            right: gap + pinX,
            bottom: gap, // anchor sits at the pin's bottom tip → no overhang below
            left: gap + pinX
        };

        const { clientWidth: w, clientHeight: h } = currentMap.getContainer();

        if (rect != null && rect.width > 0 && rect.height > 0) {
            const cardRight = rect.left + rect.width;
            const cardBottom = rect.top + rect.height;

            if (rect.width <= rect.height) {
                // Tall card → a vertical strip; reserve the left or right column.
                if (rect.left <= w - cardRight) padding.left = cardRight + gap + pinX;
                else padding.right = w - rect.left + gap + pinX;
            } else {
                // Wide card → a horizontal strip; reserve the top or bottom row.
                if (rect.top <= h - cardBottom) padding.top = cardBottom + gap + pinTop;
                else padding.bottom = h - rect.top + gap;
            }
        }

        // The strip a page draws beside the card (see map_overlay) is docked to the
        // bottom edge, so it takes the row it stands in out of the free area — on top
        // of whatever edge the card already claimed.
        if (overlay != null) {
            padding.bottom = Math.max(padding.bottom, h - overlay.top + gap);
        }

        // If the card covers (almost) the whole viewport there is no free area
        // to fit into — fall back to the base margin so fitBounds stays valid.
        const minFree = 48;
        if (padding.left + padding.right > w - minFree) { padding.left = gap + pinX; padding.right = gap + pinX; }
        if (padding.top + padding.bottom > h - minFree) { padding.top = gap + pinTop; padding.bottom = gap; }

        return padding;
    }

    /** Frames a set of coordinates inside the area the card leaves free. */
    function fitCoordinates(currentMap: mapboxgl.Map, coordinates: [number, number][]) {
        if (coordinates.length === 0) return;
        const bounds = coordinates.reduce(
            (b, c) => b.extend(c),
            new mapboxgl.LngLatBounds(coordinates[0], coordinates[0])
        );
        currentMap.fitBounds(bounds, {
            padding: cameraPadding(currentMap),
            maxZoom: 16,
            duration: 800
        });
    }

    /** Every own device, same-server share and foreign share that has a location. */
    function allCoordinates(): [number, number][] {
        const coordinates: [number, number][] = [];
        for (const device of [...webappSocket.devices, ...webappSocket.shares]) {
            const location = device.last_location;
            if (location != null) coordinates.push([location.longitude, location.latitude]);
        }
        for (const entry of foreignShares.entries) {
            const location = entry.subscription.snapshot?.last_location;
            if (location != null) coordinates.push([location.longitude, location.latitude]);
        }
        return coordinates;
    }

    /**
     * The current location of an opened target, which may be an own device, a
     * same-server share or a foreign share — so all three are searched.
     */
    function targetLocation(id: string): { longitude: number; latitude: number } | null {
        return webappSocket.devices.find((d) => d.id === id)?.last_location
            ?? webappSocket.shares.find((s) => s.id === id)?.last_location
            ?? foreignShares.entries.find((e) => e.activeShareId === id)?.subscription.snapshot?.last_location
            ?? null;
    }

    // Overview camera. Only drives anything while no device/share is open and the
    // general mode is tracking; re-runs on location updates and card resizes.
    $effect(() => {
        const currentMap = map;
        if (currentMap == null) return;
        if (mapCamera.scope !== "general" || mapCamera.generalMode !== "tracking") return;

        fitCoordinates(currentMap, allCoordinates());
    });

    // Detail camera. `tracking` follows the target at a readable zoom, `trail`
    // frames its whole history, `manual` leaves the camera alone. Reading
    // mapTrail.points only in the trail branch keeps tracking from re-running on
    // every history update.
    $effect(() => {
        const currentMap = map;
        if (currentMap == null) return;
        if (mapCamera.scope !== "detail") return;

        const mode = mapCamera.detailMode;
        if (mode === "manual") return;

        const id = mapCamera.targetId;
        if (id == null) return;
        const location = targetLocation(id);

        if (mode === "trail") {
            const coordinates: [number, number][] = mapTrail.points.map((point) => [point.longitude, point.latitude]);
            // Include where the device is now, so the frame covers the whole
            // journey even if the trail stops short of the latest position.
            if (location != null) coordinates.push([location.longitude, location.latitude]);
            fitCoordinates(currentMap, coordinates);
            return;
        }

        if (location == null) return;
        currentMap.flyTo({
            center: [location.longitude, location.latitude],
            zoom: 16,
            padding: cameraPadding(currentMap),
            duration: 800
        });
    });

    // The camera to fall back to when the detail scope closes.
    let prevTargetId: string | null = null;
    let preDetailCamera: {
        center: mapboxgl.LngLat;
        zoom: number;
        bearing: number;
        pitch: number;
    } | null = null;

    // Remember the camera when a detail view opens and restore it on leave — but
    // only if the overview is in manual mode, since nothing else would move the
    // camera back then. Under general tracking the overview effect refits instead.
    $effect(() => {
        const currentMap = map;
        if (currentMap == null) return;

        const id = mapCamera.targetId;

        if (id != null) {
            // Capture once, not on the re-runs caused by later mode changes.
            if (prevTargetId == null) {
                preDetailCamera = {
                    center: currentMap.getCenter(),
                    zoom: currentMap.getZoom(),
                    bearing: currentMap.getBearing(),
                    pitch: currentMap.getPitch()
                };
            }
        } else if (prevTargetId != null) {
            if (mapCamera.generalMode !== "tracking" && preDetailCamera != null) {
                currentMap.flyTo({ ...preDetailCamera, duration: 800 });
            }
            preDetailCamera = null;
        }

        prevTargetId = id;
    });
</script>

{#if showPlaceholder}
    <img
        src={darkMode.current ? mapDark : mapLight}
        alt=""
        class="h-full w-full object-cover object-center"
    />
{:else}
    <div
            bind:this={mapContainer}
            class="map-surface h-full w-full"
            class:show-reconnecting-animation={$isReconnecting}
    ></div>
{/if}

<style>
    /*
     * The map is drawn in full colour while the socket is live. `none` rather than
     * `grayscale(0%)`, so a live map costs no extra compositing pass — a filter of
     * any strength puts the WebGL canvas on its own layer for every frame.
     * Interpolating from `none` is well-defined: the missing function counts as its
     * identity, i.e. `grayscale(0%)`.
     */
    .map-surface {
        filter: none;
        transition: filter 200ms ease-out;
    }

    /*
     * While the connection is broken, the map desaturates and then keeps pulsing:
     * what it shows is the last known state, and a still map gives no sign of that.
     *
     * Two animations rather than one, because the way in plays once and the pulse
     * repeats. `desaturate` covers the first 200ms; `pulse-grayscale` comes later in
     * the list, so it wins over the finished `forwards` fill the moment its delay is
     * up. A running animation overrides the transition above, which is why the way in
     * is animated at all — the way out is left to the transition, since the animations
     * are simply gone by then.
     */
    .show-reconnecting-animation {
        animation:
            desaturate 200ms ease-out forwards,
            pulse-grayscale 1600ms ease-in-out 200ms infinite;
    }

    @keyframes desaturate {
        from {
            filter: grayscale(0%);
        }
        to {
            filter: grayscale(100%);
        }
    }

    @keyframes pulse-grayscale {
        0%, 100% {
            filter: grayscale(100%);
        }
        50% {
            filter: grayscale(50%);
        }
    }

    /* Holding the grey says the same thing without the movement. */
    @media (prefers-reduced-motion: reduce) {
        .show-reconnecting-animation {
            animation: desaturate 200ms ease-out forwards;
        }
    }
</style>