<script lang="ts">
    import {ArrowsOutLineHorizontalIcon, MagnifyingGlassMinusIcon, MagnifyingGlassPlusIcon, XIcon} from "phosphor-svelte";
    import type {Snippet} from "svelte";
    import {_, locale} from "svelte-i18n";
    import TimelineAxis from "./TimelineAxis.svelte";
    import TimelineScrollbar from "./TimelineScrollbar.svelte";
    import TimelineRangePicker from "./TimelineRangePicker.svelte";
    import TimelineSelection from "./TimelineSelection.svelte";
    import {type DragModifiers, timelineGestures} from "./timeline_gestures";
    import {axisFor} from "./timeline_scale";
    import {isMeaningful, rangeOf, snapToTargets, snapTargets} from "./timeline_selection";
    import {createTimelineWindow, type TimelineRange, type TimelineView} from "./timeline_window";

    let {
        oldestPoint,
        newestPoint,
        view = $bindable(null),
        selection = $bindable(null),
        actions,
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
        /**
         * The stretch the user has marked, or `null` while nothing is marked. Also
         * bindable both ways: it is what a reader of the timeline acts on, and
         * assigning to it marks a range from the outside.
         */
        selection?: TimelineRange | null;
        /**
         * Drawn in the header, before the zoom controls. For whatever the timeline is
         * about that the timeline itself has no business knowing — exporting what is
         * marked, say.
         */
        actions?: Snippet<[]>;
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

    let axis = $derived(axisFor({start: timeline.start, end: timeline.end, width, locale: $locale}));

    /** Which moment sits at [anchor], a 0…1 position across the track. */
    const timeAt = (anchor: number) => timeline.start + anchor * timeline.span;

    /**
     * Where an end dragged to [anchor] lands. It is pulled onto the nearest mark the
     * axis is drawing, unless a modifier is held: holding control (or command) is
     * how a reader says they mean exactly this moment, not the tidy one next to it.
     */
    function edgeAt(anchor: number, modifiers: DragModifiers): number {
        const time = timeAt(anchor);
        if (modifiers.ctrlKey || modifiers.metaKey) return time;
        return snapToTargets(time, snapTargets(axis, {oldest: timeline.oldest, newest: timeline.newest}), msPerPixel);
    }

    /** Where the running sweep began, or null while none is running. */
    let sweepFrom: number | null = null;

    // Gestures speak pixels; turning those into time is this component's job. The
    // handlers are stable and read the scale as they run, so the action never has to
    // be torn down and set up again.
    const gestures = {
        pan: (pixels: number) => timeline.panBy(pixels * msPerPixel),
        zoom: (factor: number, anchor: number) => timeline.zoomBy(factor, anchor),
        // Dragging the track marks a range rather than moving the window; the window
        // is moved with the wheel, a pinch or the scrollbar below.
        drag: {
            start(anchor: number, modifiers: DragModifiers) {
                sweepFrom = edgeAt(anchor, modifiers);
                selection = rangeOf(sweepFrom, sweepFrom);
            },
            move(anchor: number, modifiers: DragModifiers) {
                if (sweepFrom == null) return;
                selection = rangeOf(sweepFrom, edgeAt(anchor, modifiers));
            },
            end() {
                sweepFrom = null;
                // A press that went nowhere is a click, and a click on the track is how
                // a marked range is dropped again.
                if (selection != null && !isMeaningful(selection, msPerPixel)) selection = null;
            },
        },
    };

    /**
     * Brings a range that was set from outside the window — from the picker, mostly —
     * into view, keeping the zoom if it fits and framing the range if it does not.
     */
    function reveal(range: TimelineRange) {
        const from = range.start.getTime();
        const to = range.end.getTime();
        if (from >= timeline.start && to <= timeline.end) return;

        if (to - from > timeline.span) timeline.show(range.start, range.end);
        else timeline.set(from - (timeline.span - (to - from)) / 2, timeline.span);
    }

    /** Moves one end of the marked range, keeping the other where it is. */
    function resizeSelection(edge: "start" | "end", anchor: number, modifiers: DragModifiers) {
        if (selection == null) return;

        const fixed = edge === "start" ? selection.end.getTime() : selection.start.getTime();
        selection = rangeOf(fixed, edgeAt(anchor, modifiers));
    }

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
        <!-- What the header says is what the timeline is about: the window while
             nothing is marked, and the marked range itself once there is one — then
             as something to edit rather than only to read. -->
        {#if selection != null}
            <div class="flex min-w-0 flex-row items-center gap-1">
                <TimelineRangePicker
                        range={selection}
                        onchange={(next) => {
                            selection = next;
                            reveal(next);
                        }}
                />
                <button
                        type="button"
                        onclick={() => (selection = null)}
                        title={$_("timeline.selection.clear")}
                        aria-label={$_("timeline.selection.clear")}
                        class="flex size-6 shrink-0 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-accent"
                >
                    <XIcon size={14} />
                </button>
            </div>
        {:else}
            <span class="truncate text-xs text-muted-foreground">{rangeLabel}</span>
        {/if}

        <div class="flex shrink-0 flex-row items-center gap-1">
            {@render actions?.()}
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
            data-timeline-track
            role="region"
            tabindex="0"
            aria-label={$_("timeline.label")}
            onkeydown={onKeyDown}
            class="relative min-h-0 flex-1 cursor-grab touch-none select-none overflow-hidden rounded-2xl bg-card/40 outline-none focus-visible:ring-2 focus-visible:ring-primary/50 active:cursor-grabbing"
    >
        <TimelineAxis {axis} start={timeline.start} end={timeline.end} {width} />

        {#if selection != null}
            <TimelineSelection
                    range={selection}
                    start={timeline.start}
                    end={timeline.end}
                    {width}
                    onresize={resizeSelection}
            />
        {/if}
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
