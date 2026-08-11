<script lang="ts">
    import { onMount, mount, unmount } from "svelte";
    import { MediaQuery } from "svelte/reactivity";
    import mapboxgl from "mapbox-gl";
    import "mapbox-gl/dist/mapbox-gl.css";
    import { getMapboxToken } from "$lib/api/mapbox/get_mapbox_token";
    import { webappSocket, shareMainText } from "$lib/state/webapp_socket.svelte";
    import { foreignShares, shareOriginBase } from "$lib/state/share_socket.svelte";
    import { mapCamera, releaseCameraToUser } from "$lib/state/map_camera.svelte";
    import { mapTrail } from "$lib/state/map_trail.svelte";
    import type { HistoryPoint } from "$lib/api/history/history_repository";
    import MapPin from "./MapPin.svelte";
    import TrailPointPopover from "./TrailPointPopover.svelte";
    import mapDark from "$lib/assets/map-dark.png";
    import mapLight from "$lib/assets/map-light.png";

    let mapContainer: HTMLDivElement | null = $state(null);
    let showPlaceholder = $state(false);
    let map: mapboxgl.Map | undefined = $state();

    // One mapbox marker + mounted MapPin per device/share that has a location.
    type PinEntry = { marker: mapboxgl.Marker; component: Record<string, any> };
    const pins = new Map<string, PinEntry>();

    // MapPin's rendered SVG size. The marker is anchored at its bottom tip,
    // so a pin overhangs its coordinate by the full height upwards and half its
    // width to each side (and nothing below). Used to pad fitBounds so the whole
    // pin stays visible, not just its anchor point.
    const PIN_WIDTH = 60;
    const PIN_HEIGHT = 67;

    function removePin(id: string) {
        const entry = pins.get(id);
        if (entry == null) return;
        entry.marker.remove();
        unmount(entry.component);
        pins.delete(id);
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
    const TRAIL_POINT_SOURCE = "location-history-points";
    const TRAIL_POINT_LAYER = "location-history-points-hover";
    const TRAIL_PUCK_SOURCE = "location-history-puck";
    const TRAIL_PUCK_LAYER = "location-history-puck-dot";
    const trailColors = $derived(
        darkMode.current
            ? { line: "#e2e8f0", casing: "#020617" }
            : { line: "#0f172a", casing: "#ffffff" }
    );

    /**
     * The stretch that lies *after* the hovered position in time recedes into grey while
     * the puck is shown, so what has already been travelled up to it stands out. Grey
     * enough to read as "set aside" against both styles, and let through by a low opacity
     * rather than by a colour close to the basemap.
     */
    const trailDimmedColor = $derived(darkMode.current ? "#94a3b8" : "#64748b");

    /**
     * Stretches the optimizer has not reached yet are drawn violet: they are raw
     * measurements, still carrying the jitter and the standstill clouds that the
     * optimized part has had removed. Own hex values for the same reason as
     * {@link trailColors} — mapbox-gl cannot read the theme's oklch() tokens.
     */
    const trailRawColor = $derived(darkMode.current ? "#a78bfa" : "#7c3aed");

    // Counts style loads: the initial one and each dark-mode swap. A style change
    // drops custom sources and layers, so the trail effect depends on this to
    // know when to (re)add them. A counter rather than a boolean, so a *second*
    // load is also a change the effect can see.
    let styleEpoch = $state(0);

    // Minimal GeoJSON shape for the trail. Spelled out locally because
    // @types/geojson isn't a dependency, so the global `GeoJSON` namespace that
    // mapbox-gl's own typings reference is unavailable here.
    type TrailFeature = {
        type: "Feature";
        properties: { gap: boolean; raw: boolean; dimmed: boolean };
        geometry: { type: "LineString"; coordinates: number[][] };
    };
    type TrailData = { type: "FeatureCollection"; features: TrailFeature[] };

    /**
     * A position *on* the trail rather than one of its recorded positions: the segment
     * starting at [index], and how far along it (0–1). What the hover resolves to, and what
     * the puck is placed at.
     */
    type TrailPosition = { index: number; fraction: number };

    function interpolate(from: number[], to: number[], fraction: number): number[] {
        return [from[0] + (to[0] - from[0]) * fraction, from[1] + (to[1] - from[1]) * fraction];
    }

    /**
     * How close to a position's own coordinates a fraction has to land before it counts as
     * being *at* it. Below that the split vertex would sit on top of one that is already
     * there, which is a zero-length segment for the run splitting to trip over.
     */
    const TRAIL_SPLIT_EPSILON = 0.0005;

    /**
     * The trail cut at [position]: the boundary becomes a vertex of its own, and every
     * segment beyond it is flagged so it can be drawn apart from the rest.
     *
     * `dims` follows the same "incoming segment" convention as {@link gapFlags}, and so do
     * the copies of `gaps` and `raws` the inserted vertex needs — the segment it splits in
     * two keeps its kind on both halves.
     */
    function splitTrail(
        coordinates: number[][],
        gaps: boolean[],
        raws: boolean[],
        position: TrailPosition | null,
    ): {coordinates: number[][]; gaps: boolean[]; raws: boolean[]; dims: boolean[]} {
        const undimmed = {coordinates, gaps, raws, dims: coordinates.map(() => false)};
        // Nothing to set apart when there is no hover, or when the position sits on the last
        // drawn point — during the grow-in animation the line can be shorter than the trail.
        if (position == null || coordinates[position.index] == null) return undimmed;
        if (coordinates[position.index + 1] == null) return undimmed;

        // At a recorded position: no vertex to insert, the split runs along its own segment.
        if (position.fraction <= TRAIL_SPLIT_EPSILON) {
            return {...undimmed, dims: coordinates.map((_, i) => i > position.index)};
        }
        if (position.fraction >= 1 - TRAIL_SPLIT_EPSILON) {
            return {...undimmed, dims: coordinates.map((_, i) => i > position.index + 1)};
        }

        const at = position.index + 1;
        const boundary = interpolate(coordinates[position.index], coordinates[at], position.fraction);
        const kind = <T,>(flags: T[], fallback: T) => [
            ...flags.slice(0, at),
            flags[at] ?? fallback,
            ...flags.slice(at),
        ];

        return {
            coordinates: [...coordinates.slice(0, at), boundary, ...coordinates.slice(at)],
            gaps: kind(gaps, false),
            raws: kind(raws, false),
            // One longer than the input, and dimmed from the segment that leaves the
            // boundary vertex onwards.
            dims: Array.from({length: coordinates.length + 1}, (_, i) => i > at),
        };
    }

    /**
     * The trail's individual positions, as their own features. They exist only to be
     * hovered — the trail itself is drawn as lines, which cannot say *which* position the
     * cursor is near.
     *
     * They carry nothing but the position's place in the trail: everything else is read
     * from that list, which is also where the neighbours needed to measure against a
     * segment come from.
     */
    type TrailPointFeature = {
        type: "Feature";
        properties: { index: number };
        geometry: { type: "Point"; coordinates: number[] };
    };
    type TrailPointData = { type: "FeatureCollection"; features: TrailPointFeature[] };

    function trailPointData(points: HistoryPoint[]): TrailPointData {
        return {
            type: "FeatureCollection",
            features: points.map((point, index) => ({
                type: "Feature",
                properties: {index},
                geometry: {type: "Point", coordinates: [point.longitude, point.latitude]},
            })),
        };
    }

    /**
     * Anything longer than this between two consecutive points is a recording gap:
     * where the device actually went in between is unknown, so that stretch is
     * drawn as a faint dotted hint instead of a solid line.
     */
    const TRAIL_GAP_MS = 60_000;

    /**
     * Per-point flag: `gaps[i]` marks the segment from point `i - 1` to `i` as a
     * gap. Index 0 has no incoming segment and is always false, which keeps the
     * flags aligned with {@link toCoordinates} — the animation relies on that.
     */
    function gapFlags(points: HistoryPoint[]): boolean[] {
        return points.map((point, i) => i > 0 && point.timestamp - points[i - 1].timestamp > TRAIL_GAP_MS);
    }

    /**
     * Per-point flag in the same "incoming segment" convention as
     * {@link gapFlags}: `raws[i]` marks the segment from point `i - 1` to `i` as
     * unoptimized. The changeover segment counts as unoptimized — it is the one
     * connection no optimizer has looked at.
     */
    function rawFlags(points: HistoryPoint[]): boolean[] {
        return points.map((point, i) => i > 0 && point.is_raw);
    }

    /**
     * Splits the coordinates into one LineString per run of same-kind segments, so
     * the solid and the dotted layer can each filter for their own features. Runs
     * share their boundary point, which keeps the line visually continuous.
     */
    function trailData(
        coordinates: number[][],
        gaps: boolean[],
        raws: boolean[] = [],
        dims: boolean[] = []
    ): TrailData {
        const features: TrailFeature[] = [];
        // A LineString needs at least two positions; fewer means nothing to draw.
        let runStart = 1;
        for (let segment = 1; segment < coordinates.length; segment++) {
            const gap = gaps[segment] ?? false;
            const raw = raws[segment] ?? false;
            const dimmed = dims[segment] ?? false;
            const isLast = segment === coordinates.length - 1;
            const sameKind =
                (gaps[segment + 1] ?? false) === gap &&
                (raws[segment + 1] ?? false) === raw &&
                (dims[segment + 1] ?? false) === dimmed;
            if (!isLast && sameKind) continue;

            features.push({
                type: "Feature",
                properties: { gap, raw, dimmed },
                geometry: { type: "LineString", coordinates: coordinates.slice(runStart - 1, segment + 1) }
            });
            runStart = segment + 1;
        }
        return { type: "FeatureCollection", features };
    }

    function toCoordinates(points: HistoryPoint[]): number[][] {
        return points.map((point) => [point.longitude, point.latitude]);
    }

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
            currentMap.addSource(TRAIL_SOURCE, { type: "geojson", data: trailData([], [], []) });

            // Grey once the hover has set a stretch aside, otherwise violet where the
            // track is still raw and the theme colour where it is optimized. One
            // expression, so a stretch cannot end up in more than one of them.
            const lineColor: mapboxgl.ExpressionSpecification = [
                "case",
                ["get", "dimmed"],
                trailDimmedColor,
                ["get", "raw"],
                trailRawColor,
                trailColors.line
            ];
            // What "somewhat transparent" means, per kind of stretch.
            const lineOpacity: mapboxgl.ExpressionSpecification = ["case", ["get", "dimmed"], 0.35, 0.9];
            const gapOpacity: mapboxgl.ExpressionSpecification = ["case", ["get", "dimmed"], 0.2, 0.45];

            // `slot` positions the layers in the v3 "standard" style (which imports
            // its basemap, so it exposes no symbol layers to sort against); the
            // beforeId does the same job in the classic night style.
            const beforeId = firstSymbolLayerId(currentMap);
            // Solid stretches only — a solid casing under the dots would undo the
            // point of drawing them faintly.
            // The casing stops where the trail is set aside: a solid halo would keep the
            // stretch as present as the rest, which is the opposite of the point.
            currentMap.addLayer({
                id: TRAIL_CASING_LAYER,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                filter: ["all", ["!", ["get", "gap"]], ["!", ["get", "dimmed"]]],
                layout: { "line-cap": "round", "line-join": "round" },
                paint: { "line-color": trailColors.casing, "line-width": 7, "line-opacity": 0.7 }
            }, beforeId);
            currentMap.addLayer({
                id: TRAIL_LINE_LAYER,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                filter: ["!", ["get", "gap"]],
                layout: { "line-cap": "round", "line-join": "round" },
                paint: { "line-color": lineColor, "line-width": 3.5, "line-opacity": lineOpacity }
            }, beforeId);
            // Recording gaps: round caps plus a zero-length dash renders as dots.
            // Dash lengths are multiples of the line width, so 2 = one dot diameter
            // of spacing. `line-dasharray` takes no data-driven expression, hence a
            // layer of its own rather than a filter on the one above.
            currentMap.addLayer({
                id: TRAIL_GAP_LAYER,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                filter: ["get", "gap"],
                layout: { "line-cap": "round", "line-join": "round" },
                paint: {
                    "line-color": lineColor,
                    "line-width": 3.5,
                    "line-opacity": gapOpacity,
                    "line-dasharray": [0, 2]
                }
            }, beforeId);

            /*
             * The trail's positions as invisible features, so hovering can report which
             * one the cursor is near. Its own source: the line's data is rewritten on
             * every frame of the grow-in animation, and rebuilding tens of thousands of
             * point features at that rate would stall the map.
             *
             * The radius is deliberately tiny — what counts as "near" is decided in
             * {@link logHoveredPoint} by measuring against the cursor, not by how big
             * these are.
             */
            currentMap.addSource(TRAIL_POINT_SOURCE, {
                type: "geojson",
                data: trailPointData([]),
            });
            currentMap.addLayer({
                id: TRAIL_POINT_LAYER,
                type: "circle",
                slot: "middle",
                source: TRAIL_POINT_SOURCE,
                paint: {"circle-radius": 1, "circle-opacity": 0, "circle-stroke-width": 0}
            }, beforeId);

            // The indicator itself, added last so it sits on top of the line it marks.
            currentMap.addSource(TRAIL_PUCK_SOURCE, {type: "geojson", data: trailPuckData(null)});
            currentMap.addLayer({
                id: TRAIL_PUCK_LAYER,
                type: "circle",
                slot: "middle",
                source: TRAIL_PUCK_SOURCE,
                paint: {
                    "circle-radius": 10,
                    "circle-color": trailColors.line,
                    "circle-stroke-width": 4,
                    "circle-stroke-color": trailColors.casing
                }
            }, beforeId);
            return true;
        } catch {
            return false;
        }
    }

    /**
     * How close to the *trail* the cursor has to be to count as hovering it, in screen
     * pixels. It applies to the segments between the positions exactly as it does to the
     * positions themselves — the line is what is visible, so that is what is aimed at.
     */
    const TRAIL_HOVER_RADIUS = 10;

    /**
     * How far out to look for the endpoints of a segment when nothing is within reach.
     *
     * A segment is found through its endpoints, and the cursor can be a few pixels from a
     * long one whose ends are nowhere near it. Searching this wide from the start would
     * mean projecting thousands of positions on every mouse move wherever the trail is
     * dense (a standstill draws a whole cloud of them), so it is only the fallback for
     * having found nothing close — where, by definition, there is little to project.
     */
    const TRAIL_HOVER_SEGMENT_SEARCH = 250;

    /** Enough movement along a segment to be worth another line in the console. */
    const TRAIL_HOVER_LOG_STEP = 0.05;

    /**
     * Where on the trail the cursor is, or null when it is not near it. Drives the
     * indicator puck and the stretch that recedes behind it, and is read by
     * {@link setTrailCoordinates} on every redraw.
     */
    let hovered: TrailPosition | null = null;

    // What was last reported, so moving on within the same stretch stays quiet instead of
    // logging on every mouse event. Cleared when nothing is in reach, so coming back to a
    // position reports it again.
    let loggedPointId: string | null = null;
    let loggedProgress = 0;

    /**
     * Where on the segment [from]→[to] the cursor sits: how far along it (0–1, clamped to
     * the segment) and how far off it, both in screen pixels.
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
            distance: Math.hypot(from.x + dx * fraction - cursor.x, from.y + dy * fraction - cursor.y),
        };
    }

    /**
     * Follows the cursor along the trail: puts the indicator puck where it runs closest and
     * sets the stretch beyond that aside, or clears both once the cursor is further away
     * than {@link TRAIL_HOVER_RADIUS} from the whole line.
     *
     * In development it also reports what it found — the recorded position the cursor is
     * nearest to, and where between that position and one of its neighbours the cursor
     * sits: `progress` runs from 0 to 1 towards the *next* position and from 0 to -1
     * towards the *previous* one. The nearer end of a segment is the position reported, so
     * in practice the value stays within half a segment either way.
     */
    function updateHoveredPoint(event: mapboxgl.MapMouseEvent) {
        const currentMap = map;
        // Bound to the map, not to the layer, so it also fires beside the trail rather
        // than only on it. The layer is gone between style swaps and while no trail is
        // shown, and then there is nothing to search.
        if (currentMap == null || currentMap.getLayer(TRAIL_POINT_LAYER) == null) return;

        const trailPoints = mapTrail.points;
        const cursor = event.point;

        /*
         * A box is what the query takes. Its result is read as the features
         * {@link trailPointData} wrote — mapbox-gl's own feature type resolves to nothing
         * useful here, for the same reason {@link TrailFeature} is spelled out. Querying
         * rather than walking the whole list also keeps positions on the far side of the
         * globe out of it: they are not rendered, so they are not returned.
         */
        const positionsWithin = (radius: number) => currentMap.queryRenderedFeatures(
            [[cursor.x - radius, cursor.y - radius], [cursor.x + radius, cursor.y + radius]],
            {layers: [TRAIL_POINT_LAYER]},
        ) as unknown as TrailPointFeature[];

        // Close by first; only if the cursor is near no position at all is it worth looking
        // for the far-apart ends of a long segment. See TRAIL_HOVER_SEGMENT_SEARCH.
        const candidates = positionsWithin(TRAIL_HOVER_RADIUS);
        const searched = candidates.length > 0 ? candidates : positionsWithin(TRAIL_HOVER_SEGMENT_SEARCH);

        // Neighbouring candidates share endpoints, so each position is projected once.
        const projected = new Map<number, mapboxgl.Point>();
        const project = (index: number) => {
            let point = projected.get(index);
            if (point == null) {
                point = currentMap.project([trailPoints[index].longitude, trailPoints[index].latitude]);
                projected.set(index, point);
            }
            return point;
        };

        // The segment that starts at `index`, or the bare position when it has no next one.
        let nearest: {index: number; fraction: number; distance: number} | null = null;
        const consider = (index: number) => {
            if (trailPoints[index] == null) return;

            const from = project(index);
            const {fraction, distance} = trailPoints[index + 1] == null
                ? {fraction: 0, distance: Math.hypot(from.x - cursor.x, from.y - cursor.y)}
                : nearestOnSegment(from, project(index + 1), cursor);

            if (distance > TRAIL_HOVER_RADIUS) return;
            if (nearest == null || distance < nearest.distance) nearest = {index, fraction, distance};
        };

        for (const candidate of searched) {
            const index = candidate.properties.index;
            // The trail may have been replaced since the query — an index is only an index.
            if (trailPoints[index] == null) continue;

            consider(index - 1);
            consider(index);
        }

        if (nearest == null) {
            loggedPointId = null;
            setHovered(currentMap, null);
            return;
        }

        const {index: segmentStart, fraction, distance} = nearest;
        setHovered(currentMap, {index: segmentStart, fraction});

        if (!import.meta.env.DEV) return;

        const {index, progress} = anchorOf({index: segmentStart, fraction});
        const point = trailPoints[index];
        const neighbour = trailPoints[progress >= 0 ? index + 1 : index - 1] ?? null;

        if (point.id === loggedPointId && Math.abs(progress - loggedProgress) < TRAIL_HOVER_LOG_STEP) return;
        loggedPointId = point.id;
        loggedProgress = progress;

        console.log("trail point", {
            id: point.id,
            index,
            raw: point.is_raw,
            timestamp: point.timestamp,
            recordedAt: new Date(point.timestamp).toISOString(),
            progress: Math.round(progress * 1000) / 1000,
            towards: neighbour?.id ?? null,
            distancePx: Math.round(distance * 10) / 10,
        });
    }

    /**
     * Moves the puck and the boundary of the set-aside stretch, redrawing only when
     * something actually changed — a mouse move within the same fraction of the same
     * segment leaves the map alone.
     *
     * While the grow-in animation runs, its next frame redraws the line anyway; only the
     * puck is moved here.
     */
    function setHovered(currentMap: mapboxgl.Map, position: TrailPosition | null) {
        if (position?.index === hovered?.index && position?.fraction === hovered?.fraction) return;

        hovered = position;
        setTrailPuck(currentMap, position);

        if (trailFrame != null) return;

        const points = mapTrail.points;
        setTrailCoordinates(currentMap, toCoordinates(points), gapFlags(points), rawFlags(points));
    }

    function setTrailCoordinates(
        currentMap: mapboxgl.Map,
        coordinates: number[][],
        gaps: boolean[] = [],
        raws: boolean[] = []
    ) {
        const source = currentMap.getSource(TRAIL_SOURCE);
        if (source?.type !== "geojson") return;

        // The hovered position is read here rather than passed in, so every redraw — a
        // frame of the grow-in animation included — sets the same stretch aside.
        const split = splitTrail(coordinates, gaps, raws, hovered);
        source.setData(trailData(split.coordinates, split.gaps, split.raws, split.dims));
    }

    /** A collection of one point, or of none while nothing is hovered. */
    function trailPuckData(coordinate: number[] | null): TrailPointData {
        return {
            type: "FeatureCollection",
            features: coordinate == null
                ? []
                : [{
                    type: "Feature",
                    properties: {index: 0},
                    geometry: {type: "Point", coordinates: coordinate}
                }],
        };
    }

    /**
     * What the popover above the puck describes. A container that is mutated rather than a
     * prop that is passed: the popover is mounted imperatively, so this is what carries a
     * change into it.
     */
    const popoverState = $state<{point: HistoryPoint | null}>({point: null});
    let popover: {marker: mapboxgl.Marker; component: Record<string, any>} | null = null;

    /**
     * The recorded position a hover is anchored on: the nearer end of the segment it landed
     * on, plus how far the cursor sits from it — forwards to the next position as a positive
     * fraction, backwards to the previous one as a negative one.
     */
    function anchorOf(position: TrailPosition): {index: number; progress: number} {
        const forwards = position.fraction <= 0.5;
        return {
            index: forwards ? position.index : position.index + 1,
            progress: forwards ? position.fraction : position.fraction - 1,
        };
    }

    /**
     * Puts the indicator on [position] and the popover above it, or takes both off the map
     * for null. The popover reports the recorded position nearest the cursor, which the puck
     * itself may sit a little way from — it follows the line, not the data.
     */
    function setTrailPuck(currentMap: mapboxgl.Map, position: TrailPosition | null) {
        const source = currentMap.getSource(TRAIL_PUCK_SOURCE);
        if (source?.type !== "geojson") return;

        const points = mapTrail.points;
        const from = position == null ? null : points[position.index];
        const to = position == null ? null : points[position.index + 1];

        if (from == null) {
            source.setData(trailPuckData(null));
            popoverState.point = null;
            return;
        }

        // Interpolated in coordinates, the same way the line between the two is drawn, so
        // the puck sits on it rather than beside it.
        const start = [from.longitude, from.latitude];
        const at = to == null ? start : interpolate(start, [to.longitude, to.latitude], position!.fraction);

        source.setData(trailPuckData(at));
        popoverState.point = points[anchorOf(position!).index] ?? from;
        trailPopover(currentMap).setLngLat(at as [number, number]);
    }

    /**
     * The popover's marker, mounted on first use. A marker rather than a box positioned by
     * hand, so it stays glued to its coordinate while the map is panned or zoomed — and it
     * survives a style swap, which drops layers but not markers.
     */
    function trailPopover(currentMap: mapboxgl.Map): mapboxgl.Marker {
        if (popover != null) return popover.marker;

        const element = document.createElement("div");
        // Mapbox positions the element; anything the cursor could catch on it would break
        // the hover that produced it.
        element.style.pointerEvents = "none";

        const component = mount(TrailPointPopover, {target: element, props: {state: popoverState}});
        // Clear of the puck: its outer edge is the radius plus its ring.
        const marker = new mapboxgl.Marker({element, anchor: "bottom", offset: [0, -16]})
            .setLngLat([0, 0])
            .addTo(currentMap);

        popover = {marker, component};
        return marker;
    }

    /** The hit targets for {@link logHoveredPoint}; see {@link TRAIL_POINT_SOURCE}. */
    function setTrailPoints(currentMap: mapboxgl.Map, points: HistoryPoint[]) {
        const source = currentMap.getSource(TRAIL_POINT_SOURCE);
        if (source?.type === "geojson") source.setData(trailPointData(points));
        loggedPointId = null;
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

    /**
     * Grows the trail in from its oldest point over {@link TRAIL_ANIMATION_MS}, counted
     * from [animateFrom]. Passing the start of an animation that is already running
     * carries it on with the new geometry; null draws the finished line at once.
     */
    function drawTrail(currentMap: mapboxgl.Map, points: HistoryPoint[], animateFrom: number | null) {
        cancelTrailAnimation();

        const coordinates = toCoordinates(points);
        const gaps = gapFlags(points);
        const raws = rawFlags(points);
        if (animateFrom == null || coordinates.length < 2) {
            trailAnimationStart = null;
            setTrailCoordinates(currentMap, coordinates, gaps, raws);
            return;
        }

        trailAnimationStart = animateFrom;
        const lengths = cumulativeLengths(coordinates);
        // The truncated head keeps the original point indices (its interpolated tip sits
        // in the segment it replaces), so `gaps` still lines up.
        const drawUpTo = (now: number) => {
            const t = Math.min(1, (now - animateFrom) / TRAIL_ANIMATION_MS);
            setTrailCoordinates(currentMap, trailUpTo(coordinates, lengths, easeOutExpo(t)), gaps, raws);
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

            // Registered once for the map's whole life, not with the layer: the layer is
            // added again after every style swap, and so would the listener be.
            map.on("mousemove", updateHoveredPoint);
            // Leaving the map is not a mouse move, so the puck would stay behind.
            map.on("mouseout", () => setHovered(map!, null));

            // Fires for the initial style and again after every setStyle.
            map.on("style.load", () => styleEpoch++);
        });

        return () => {
            cancelTrailAnimation();
            for (const id of [...pins.keys()]) removePin(id);
            if (popover != null) {
                popover.marker.remove();
                unmount(popover.component);
                popover = null;
            }
            map?.remove();
        };
    });

    $effect(() => {
        map?.setStyle(style);
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
        const points = mapTrail.points;
        const trailKey = mapTrail.key;

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

        // A different track is not the one that was hovered, and after a style swap the
        // re-added puck layer starts out empty — either way it is put back from here.
        if (isNewTrail) hovered = null;

        drawTrail(currentMap, points, animateFrom);
        setTrailPuck(currentMap, hovered);
        // Independent of the animation: the hit targets are the whole trail from the
        // start, so hovering does not have to wait for the line to arrive.
        setTrailPoints(currentMap, points);

        return () => {
            cancelTrailAnimation();
            // The map (or just its style) may already be gone — on component
            // teardown, or mid-swap between two styles.
            try {
                setTrailCoordinates(currentMap, []);
                setTrailPoints(currentMap, []);
                setTrailPuck(currentMap, null);
            } catch {
                // Nothing to clear.
            }
        };
    });

    // Add or move the marker for one entity (own device or share). The pin id
    // is the entity's own id; each kind renders its own pin component.
    function upsertPin(
        currentMap: mapboxgl.Map,
        id: string,
        location: { longitude: number; latitude: number },
        makeComponent: (target: HTMLElement) => Record<string, any>
    ) {
        const lngLat: [number, number] = [location.longitude, location.latitude];
        const existing = pins.get(id);
        if (existing != null) {
            existing.marker.setLngLat(lngLat);
            return;
        }

        const element = document.createElement("div");
        const component = makeComponent(element);
        const marker = new mapboxgl.Marker({ element, anchor: "bottom" })
            .setLngLat(lngLat)
            .addTo(currentMap);
        pins.set(id, { marker, component });
    }

    // Keep a pin on the map for every own device and share that has a location.
    $effect(() => {
        const currentMap = map;
        if (currentMap == null) return;

        const seen = new Set<string>();

        for (const device of webappSocket.devices) {
            const location = device.last_location;
            if (location == null) continue;
            seen.add(device.id);
            upsertPin(currentMap, device.id, location, (target) =>
                mount(MapPin, {
                    target,
                    props: {
                        id: device.id,
                        label: device.name,
                        imageUrl: `/api/v1/devices/image/${device.manufacturer}-${device.model}`,
                        href: `/devices/${device.id}`
                    }
                })
            );
        }

        for (const share of webappSocket.shares) {
            const location = share.last_location;
            if (location == null) continue;
            seen.add(share.id);
            upsertPin(currentMap, share.id, location, (target) =>
                mount(MapPin, {
                    target,
                    props: {
                        id: share.id,
                        label: shareMainText(share),
                        imageUrl: `/api/v1/devices/image/${share.manufacturer}-${share.model}`,
                        href: `/share/${share.id}`
                    }
                })
            );
        }

        for (const entry of foreignShares.entries) {
            const snapshot = entry.subscription.snapshot;
            const location = snapshot?.last_location;
            if (snapshot == null || location == null) continue;
            seen.add(entry.activeShareId);
            const base = shareOriginBase(entry.homeserver);
            upsertPin(currentMap, entry.activeShareId, location, (target) =>
                mount(MapPin, {
                    target,
                    props: {
                        id: entry.activeShareId,
                        label: shareMainText(snapshot),
                        imageUrl: `${base}/api/v1/devices/image/${snapshot.manufacturer}-${snapshot.model}`,
                        href: `/share/${entry.activeShareId}?homeserver=${encodeURIComponent(entry.homeserver)}`
                    }
                })
            );
        }

        // Drop pins for entities that vanished or lost their location.
        for (const id of [...pins.keys()]) {
            if (!seen.has(id)) removePin(id);
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

        const padding = {
            top: gap + pinTop,
            right: gap + pinX,
            bottom: gap, // anchor sits at the pin's bottom tip → no overhang below
            left: gap + pinX
        };
        if (rect == null || rect.width === 0 || rect.height === 0) return padding;

        const { clientWidth: w, clientHeight: h } = currentMap.getContainer();
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
    <div bind:this={mapContainer} class="h-full w-full"></div>
{/if}
