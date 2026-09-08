<script lang="ts">
    import {ArrowsOutLineHorizontalIcon, MagnifyingGlassMinusIcon, MagnifyingGlassPlusIcon} from "phosphor-svelte";
    import {_, locale} from "svelte-i18n";
    import TimelineAxis from "./TimelineAxis.svelte";
    import TimelineScrollbar from "./TimelineScrollbar.svelte";
    import {timelineGestures} from "./timeline_gestures";
    import {createTimelineWindow, type TimelineView} from "./timeline_window";

    let {
        oldestPoint,
        newestPoint,
        view = $bindable(null),
    }: {
        /** First moment there is data for. */
        oldestPoint: Date;
        /** Last moment there is data for. */
        newestPoint: Date;
        /**
         * The window on show — the whole state of the timeline, and the only thing
         * that moves it. Bindable in both directions: read it to know what the user
         * is looking at, assign to it to send them somewhere else. It stays `null`
         * until the track has been measured, and is filled with the opening window.
         */
        view?: TimelineView | null;
    } = $props();

    /** How much history the timeline opens on, at most. */
    const OPENING_SPAN = 7 * 24 * 60 * 60 * 1_000;

    /** What one press of a zoom button, or one +/- keystroke, does. */
    const ZOOM_STEP = 0.8;

    /** What one arrow key does, as a share of the window. */
    const PAN_STEP = 0.1;

    // The window lives in the bindable prop and the model only reads and writes it,
    // so there is one source of truth no matter which side moves the timeline.
    const timeline = createTimelineWindow({
        bounds: () => ({oldest: oldestPoint, newest: newestPoint}),
        view: () => view,
        publish: (next) => (view = next),
    });

    let width = $state(0);
    let msPerPixel = $derived(width > 0 ? timeline.span / width : 0);

    // Gestures speak pixels; turning those into time is this component's job. The
    // handlers are stable and read the scale as they run, so the action never has to
    // be torn down and set up again.
    const gestures = {
        pan: (pixels: number) => timeline.panBy(pixels * msPerPixel),
        zoom: (factor: number, anchor: number) => timeline.zoomBy(factor, anchor),
    };

    // Open on the most recent week, or on the whole history if it is shorter: months
    // of points squeezed into one strip say nothing, and what a device did lately is
    // what a reader looks for first. Only ever runs while there is no window — a
    // later history update must not yank the view away from where the user scrolled.
    $effect(() => {
        if (view == null && width > 0) timeline.openOn(OPENING_SPAN);
    });

    function onKeyDown(event: KeyboardEvent) {
        const handlers: Record<string, () => void> = {
            ArrowLeft: () => timeline.panBy(-timeline.span * PAN_STEP),
            ArrowRight: () => timeline.panBy(timeline.span * PAN_STEP),
            "+": () => timeline.zoomBy(ZOOM_STEP),
            "-": () => timeline.zoomBy(1 / ZOOM_STEP),
            Home: () => timeline.show(oldestPoint, new Date(timeline.oldest + timeline.span)),
            End: () => timeline.show(new Date(timeline.newest - timeline.span), newestPoint),
        };

        const handler = handlers[event.key];
        if (handler == null) return;

        event.preventDefault();
        handler();
    }

    let rangeLabel = $derived(
        view == null
            ? ""
            : new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"})
                .formatRange(view.start, view.end),
    );
</script>

<div class="flex h-full w-full flex-col gap-1 p-2">
    <div class="flex flex-row items-center justify-between gap-2 px-1">
        <span class="truncate text-xs text-muted-foreground">{rangeLabel}</span>

        <div class="flex shrink-0 flex-row items-center gap-1">
            {@render control($_("timeline.zoom_out"), () => timeline.zoomBy(1 / ZOOM_STEP), MagnifyingGlassMinusIcon)}
            {@render control($_("timeline.zoom_in"), () => timeline.zoomBy(ZOOM_STEP), MagnifyingGlassPlusIcon)}
            {@render control($_("timeline.fit"), () => timeline.fit(), ArrowsOutLineHorizontalIcon)}
        </div>
    </div>

    <!-- `touch-none` hands every finger to the gestures above: without it the browser
         would scroll the page instead of the timeline. -->
    <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <!-- A scrollable region is focusable on purpose (ARIA APG): the arrow keys and
         +/- are the keyboard equivalent of the drag and pinch gestures. -->
    <div
            bind:clientWidth={width}
            use:timelineGestures={gestures}
            role="region"
            tabindex="0"
            aria-label={$_("timeline.label")}
            onkeydown={onKeyDown}
            class="relative min-h-0 flex-1 cursor-grab touch-none select-none overflow-hidden rounded-2xl bg-card/40 outline-none focus-visible:ring-2 focus-visible:ring-primary/50 active:cursor-grabbing"
    >
        <TimelineAxis start={timeline.start} end={timeline.end} {width} />
    </div>

    <TimelineScrollbar {timeline} />
</div>

{#snippet control(label: string, press: () => void, Icon: typeof MagnifyingGlassPlusIcon)}
    <button
            type="button"
            onclick={press}
            title={label}
            aria-label={label}
            class="flex size-7 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-accent"
    >
        <Icon size={16} />
    </button>
{/snippet}
