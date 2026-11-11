<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import MovementLane from "$lib/app/devices/MovementLane.svelte";
    import RangeShortcuts from "$lib/app/shell/map/RangeShortcuts.svelte";
    import {formatDistance} from "$lib/app/movements";
    import {
        axisFor,
        createTimelineWindow,
        TimelineAxis,
        timelineGestures,
        type TimelineRange,
        type TimelineView,
    } from "$lib/components/timeline";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";
    import LabEpisodeIcon from "./LabEpisodeIcon.svelte";
    import LabModeSwitch from "./LabModeSwitch.svelte";
    import LabOverview from "./LabOverview.svelte";
    import {episodeAt, episodesBetween, formatDuration, type LabHistory} from "./lab_history";

    /**
     * Proposal B — a scrubber with focus and context. The whole history sits on top as
     * an overview with a brush that frames the detail below; the detail has a fixed
     * needle in its centre, the history slides underneath it, and the readout says what
     * was happening at the needle. Display mode and shortcuts share one bar.
     */
    let {
        history,
        mode = $bindable(),
        focus = $bindable(),
    }: {
        history: LabHistory;
        mode: TrailDisplayMode;
        /** What the map would frame. */
        focus: TimelineRange | null;
    } = $props();

    const DAY = 24 * 60 * 60 * 1_000;

    /** What one arrow key does, as a share of the window. */
    const PAN_STEP = 0.05;

    // Opens on a day, with the needle on the last moment.
    let view = $state<TimelineView>(opening());

    function opening(): TimelineView {
        return {start: new Date(history.newest - DAY / 2), end: new Date(history.newest + DAY / 2)};
    }

    // The bounds reach half a window past either end, so the needle in the middle can
    // be put on the very first and the very last moment.
    const timeline = createTimelineWindow({
        bounds: () => {
            const half = (view.end.getTime() - view.start.getTime()) / 2;
            return {oldest: new Date(history.oldest - half), newest: new Date(history.newest + half)};
        },
        view: () => view,
        publish: (next) => (view = next),
        closestSpan: 30 * 60 * 1_000,
    });

    let width = $state(0);
    let msPerPixel = $derived(width > 0 ? timeline.span / width : 0);
    let axis = $derived(axisFor({start: timeline.start, end: timeline.end, width, locale: $locale}));

    /** The moment under the needle. */
    let needle = $derived(Math.min(Math.max(timeline.start + timeline.span / 2, history.oldest), history.newest));
    let current = $derived(episodeAt(history.episodes, needle));

    $effect(() => {
        focus = {start: view.start, end: view.end};
    });

    // The detail is a scrubber: a one-finger or mouse drag moves the history under the
    // needle, a pinch or a modified wheel zooms around it.
    const gestures = {
        pan: (pixels: number) => timeline.panBy(pixels * msPerPixel),
        zoom: (factor: number) => timeline.zoomBy(factor, 0.5),
    };

    function onKeyDown(event: KeyboardEvent) {
        const handlers: Record<string, () => void> = {
            ArrowLeft: () => timeline.panBy(-timeline.span * PAN_STEP),
            ArrowRight: () => timeline.panBy(timeline.span * PAN_STEP),
            "+": () => timeline.zoomBy(0.8),
            "-": () => timeline.zoomBy(1.25),
        };
        const handler = handlers[event.key];
        if (handler == null) return;
        event.preventDefault();
        handler();
    }

    let overviewWidth = $state(0);
    const overviewPercent = (time: number) => ((time - history.oldest) / (history.newest - history.oldest)) * 100;

    /** What a press on the overview is doing until it is let go. */
    let brushDrag: {kind: "move" | "start" | "end"; x: number; start: number; end: number} | null = null;

    function onOverviewDown(event: PointerEvent, kind: "move" | "start" | "end" | "jump") {
        event.stopPropagation();
        if (overviewWidth <= 0) return;
        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);

        if (kind === "jump") {
            // A press beside the brush centres the window there, then drags it along.
            const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
            const at = history.oldest + ((event.clientX - rect.left) / rect.width) * (history.newest - history.oldest);
            timeline.set(at - timeline.span / 2, timeline.span);
            kind = "move";
        }
        brushDrag = {kind, x: event.clientX, start: timeline.start, end: timeline.end};
    }

    function onOverviewMove(event: PointerEvent) {
        if (brushDrag == null) return;
        const delta = ((event.clientX - brushDrag.x) / overviewWidth) * (history.newest - history.oldest);
        if (brushDrag.kind === "move") timeline.set(brushDrag.start + delta, brushDrag.end - brushDrag.start);
        else if (brushDrag.kind === "start") timeline.set(brushDrag.start + delta, brushDrag.end - brushDrag.start - delta);
        else timeline.set(brushDrag.start, brushDrag.end - brushDrag.start + delta);
    }

    const onOverviewUp = () => (brushDrag = null);

    const EDGES = ["start", "end"] as const;
    let brushLeft = $derived(Math.max(0, overviewPercent(timeline.start)));
    let brushRight = $derived(Math.min(100, overviewPercent(timeline.end)));

    let needleFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {weekday: "short", hour: "2-digit", minute: "2-digit"}));
    let rangeFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {hour: "2-digit", minute: "2-digit"}));

    let detailEpisodes = $derived(episodesBetween(history.episodes, timeline.start, timeline.end));
    const px = (time: number) => (time - timeline.start) / Math.max(msPerPixel, 1e-9);
</script>

<div class="flex flex-col gap-3">
    <!-- One bar for how and what: the display mode, then the ranges. -->
    <div class="flex flex-row flex-wrap items-center gap-2">
        <LabModeSwitch bind:mode />
        <RangeShortcuts
                grantSeconds={null}
                oldest={new Date(history.oldest)}
                newest={new Date(history.newest)}
                {view}
                onselect={(range) => timeline.show(range.start, range.end)}
        />
    </div>

    <!-- Context: the whole history, with the brush that frames the detail. -->
    <div class="flex flex-col gap-1">
        <span class="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{$_("lab.timeline.scrubber.overview")}</span>
        <div
                bind:clientWidth={overviewWidth}
                role="presentation"
                onpointerdown={(event) => onOverviewDown(event, "jump")}
                onpointermove={onOverviewMove}
                onpointerup={onOverviewUp}
                onpointercancel={onOverviewUp}
                class="relative h-8 cursor-pointer touch-none select-none pointer-coarse:h-11"
        >
            <LabOverview {history} />
            <div
                    role="slider"
                    tabindex="-1"
                    aria-label={$_("lab.timeline.scrubber.window")}
                    aria-valuemin={history.oldest}
                    aria-valuemax={history.newest}
                    aria-valuenow={needle}
                    aria-valuetext={needleFormat.format(needle)}
                    onpointerdown={(event) => onOverviewDown(event, "move")}
                    onpointermove={onOverviewMove}
                    onpointerup={onOverviewUp}
                    onpointercancel={onOverviewUp}
                    class="absolute inset-y-0 cursor-grab rounded-md border-2 border-primary bg-primary/15 active:cursor-grabbing"
                    style:left="{brushLeft}%"
                    style:width="max(10px, {brushRight - brushLeft}%)"
            >
                {#each EDGES as edge (edge)}
                    <span
                            role="presentation"
                            onpointerdown={(event) => onOverviewDown(event, edge)}
                            onpointermove={onOverviewMove}
                            onpointerup={onOverviewUp}
                            onpointercancel={onOverviewUp}
                            class="absolute inset-y-0 w-3 cursor-ew-resize pointer-coarse:w-5 {edge === 'start' ? '-left-2 pointer-coarse:-left-3' : '-right-2 pointer-coarse:-right-3'}"
                    ></span>
                {/each}
            </div>
        </div>
    </div>

    <!-- Readout: what was happening at the needle. -->
    <div aria-live="polite" class="flex min-h-10 flex-row items-center gap-2 rounded-xl bg-card/70 px-3 py-2 text-sm">
        <span class="shrink-0 tabular-nums font-medium">{needleFormat.format(needle)}</span>
        <span class="text-muted-foreground">·</span>
        {#if current == null}
            <span class="text-muted-foreground">{$_("lab.timeline.episodes.no_data")}</span>
        {:else}
            <span class="flex min-w-0 flex-row items-center gap-1.5 truncate">
                <LabEpisodeIcon episode={current} />
                {#if current.kind === "stay"}
                    {$_("lab.timeline.episodes.stay", {values: {place: $_(`lab.timeline.places.${current.place}`)}})}
                    <span class="text-muted-foreground">· {formatDuration(current.to - current.from, $locale)}</span>
                {:else if current.kind === "gap"}
                    {$_("lab.timeline.episodes.no_data_for", {values: {duration: formatDuration(current.to - current.from, $locale)}})}
                {:else}
                    {$_(`history.movements.type.${current.movement.type}`)}
                    <span class="text-muted-foreground">
                        · {formatDistance(current.movement.distance_meters, $locale)} · {rangeFormat.formatRange(current.from, current.to)}
                    </span>
                {/if}
            </span>
        {/if}
    </div>

    <!-- Focus: the detail, slid underneath a fixed needle. -->
    <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <div
            bind:clientWidth={width}
            use:timelineGestures={gestures}
            role="region"
            tabindex="0"
            aria-label={$_("lab.timeline.scrubber.detail")}
            onkeydown={onKeyDown}
            class="relative h-32 cursor-grab touch-none select-none overflow-hidden rounded-2xl bg-card/40 outline-none focus-visible:ring-2 focus-visible:ring-primary/50 active:cursor-grabbing"
    >
        <TimelineAxis {axis} start={timeline.start} end={timeline.end} {width} />

        <!-- Before the first and after the last moment there is nothing to scrub. -->
        {#if timeline.start < history.oldest}
            <div class="absolute inset-y-0 left-0 bg-background/70" style:width="{px(history.oldest)}px"></div>
        {/if}
        {#if timeline.end > history.newest}
            <div class="absolute inset-y-0 right-0 bg-background/70" style:left="{px(history.newest)}px"></div>
        {/if}

        <!-- Where the person was… -->
        <div class="absolute inset-x-0 top-6 h-5 text-[10px] text-muted-foreground">
            {#each detailEpisodes as episode (episode.id)}
                {@const left = Math.max(0, px(episode.from))}
                {@const right = Math.min(width, px(episode.to))}
                {#if episode.kind === "gap"}
                    <div class="lab-gap absolute top-0 h-[4.25rem] rounded" style:left="{left}px" style:width="{right - left}px"></div>
                {:else if episode.kind === "stay" && right - left >= 40}
                    <span class="absolute truncate rounded bg-muted-foreground/10 px-1 leading-5" style:left="{left}px" style:width="{right - left - 2}px">
                        {$_(`lab.timeline.places.${episode.place}`)}
                    </span>
                {/if}
            {/each}
        </div>

        <!-- …and how they moved in between, with the production lane. -->
        <div class="absolute inset-x-0 top-12 h-8">
            <MovementLane movements={history.movements} scale={{start: timeline.start, end: timeline.end, width}} />
        </div>

        <span class="absolute inset-x-0 bottom-1.5 text-center text-[10px] text-muted-foreground">{$_("lab.timeline.scrubber.hint")}</span>

        <div class="pointer-events-none absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 bg-primary"></div>
        <div class="pointer-events-none absolute left-1/2 top-0 size-2.5 -translate-x-1/2 rotate-45 bg-primary"></div>
    </div>
</div>
