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
    const trailColors = $derived(
        darkMode.current
            ? { line: "#e2e8f0", casing: "#020617" }
            : { line: "#0f172a", casing: "#ffffff" }
    );

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
        properties: { gap: boolean; raw: boolean };
        geometry: { type: "LineString"; coordinates: number[][] };
    };
    type TrailData = { type: "FeatureCollection"; features: TrailFeature[] };

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
    function trailData(coordinates: number[][], gaps: boolean[], raws: boolean[] = []): TrailData {
        const features: TrailFeature[] = [];
        // A LineString needs at least two positions; fewer means nothing to draw.
        let runStart = 1;
        for (let segment = 1; segment < coordinates.length; segment++) {
            const gap = gaps[segment] ?? false;
            const raw = raws[segment] ?? false;
            const isLast = segment === coordinates.length - 1;
            const sameKind =
                (gaps[segment + 1] ?? false) === gap && (raws[segment + 1] ?? false) === raw;
            if (!isLast && sameKind) continue;

            features.push({
                type: "Feature",
                properties: { gap, raw },
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

            // Violet where the track is still raw, the theme colour where it is
            // optimized. One expression, so a stretch cannot end up in both.
            const lineColor: mapboxgl.ExpressionSpecification = [
                "case",
                ["get", "raw"],
                trailRawColor,
                trailColors.line
            ];

            // `slot` positions the layers in the v3 "standard" style (which imports
            // its basemap, so it exposes no symbol layers to sort against); the
            // beforeId does the same job in the classic night style.
            const beforeId = firstSymbolLayerId(currentMap);
            // Solid stretches only — a solid casing under the dots would undo the
            // point of drawing them faintly.
            currentMap.addLayer({
                id: TRAIL_CASING_LAYER,
                type: "line",
                slot: "middle",
                source: TRAIL_SOURCE,
                filter: ["!", ["get", "gap"]],
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
                paint: { "line-color": lineColor, "line-width": 3.5, "line-opacity": 0.9 }
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
                    "line-opacity": 0.45,
                    "line-dasharray": [0, 2]
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
        gaps: boolean[] = [],
        raws: boolean[] = []
    ) {
        const source = currentMap.getSource(TRAIL_SOURCE);
        if (source?.type === "geojson") source.setData(trailData(coordinates, gaps, raws));
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

            // Fires for the initial style and again after every setStyle.
            map.on("style.load", () => styleEpoch++);
        });

        return () => {
            cancelTrailAnimation();
            for (const id of [...pins.keys()]) removePin(id);
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

        drawTrail(currentMap, points, animateFrom);

        return () => {
            cancelTrailAnimation();
            // The map (or just its style) may already be gone — on component
            // teardown, or mid-swap between two styles.
            try {
                setTrailCoordinates(currentMap, []);
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
