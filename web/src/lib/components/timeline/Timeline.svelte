<script module lang="ts">
    /** The window a timeline currently shows. */
    export interface TimelineView {
        start: Date;
        end: Date;
    }
</script>

<script lang="ts">
    import {ArrowsOutLineHorizontalIcon, MagnifyingGlassMinusIcon, MagnifyingGlassPlusIcon} from "phosphor-svelte";
    import {MediaQuery} from "svelte/reactivity";
    import {fade} from "svelte/transition";
    import {_, locale} from "svelte-i18n";

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

    const SECOND = 1_000;
    const MINUTE = 60 * SECOND;
    const HOUR = 60 * MINUTE;
    const DAY = 24 * HOUR;

    /** Tightest window the user can zoom to, unless the history is shorter still. */
    const CLOSEST_SPAN = 10 * SECOND;

    /** How much history the timeline opens on, at most. */
    const OPENING_SPAN = 7 * DAY;

    /**
     * Roughly how much room one labelled tick wants, in pixels. Tight on purpose:
     * the labels are short, and a wider setting makes whole steps of the ladder
     * unreachable — a week-long window would be labelled once, with its week.
     */
    const LABEL_SPACING = 80;

    /** The units the axis is divided by, from a second up to a year. */
    type Unit = "second" | "minute" | "hour" | "day" | "week" | "month" | "year";

    interface Scale {
        /** What one labelled tick stands for. */
        unit: Unit;
        /** How many of them lie between two ticks. Always divides its unit evenly. */
        every: number;
        /**
         * The coarser unit whose boundaries are drawn as separators. An axis of hours
         * is only readable while it says which day they belong to, and that is what
         * the separator and its label are for.
         */
        separator: Unit;
        /** Rough length in milliseconds. Only used to pick a scale for a zoom level. */
        length: number;
    }

    /**
     * The zoom ladder. Ticks are placed on real calendar boundaries rather than on
     * multiples of a duration, so a day starts at local midnight, a month on its
     * first, and neither daylight saving nor a 31-day month shifts anything.
     */
    const SCALES: Scale[] = [
        {unit: "second", every: 1, separator: "minute", length: SECOND},
        {unit: "second", every: 5, separator: "minute", length: 5 * SECOND},
        {unit: "second", every: 15, separator: "minute", length: 15 * SECOND},
        {unit: "second", every: 30, separator: "minute", length: 30 * SECOND},
        {unit: "minute", every: 1, separator: "hour", length: MINUTE},
        {unit: "minute", every: 5, separator: "hour", length: 5 * MINUTE},
        {unit: "minute", every: 15, separator: "hour", length: 15 * MINUTE},
        {unit: "minute", every: 30, separator: "hour", length: 30 * MINUTE},
        {unit: "hour", every: 1, separator: "day", length: HOUR},
        {unit: "hour", every: 3, separator: "day", length: 3 * HOUR},
        {unit: "hour", every: 6, separator: "day", length: 6 * HOUR},
        {unit: "hour", every: 12, separator: "day", length: 12 * HOUR},
        {unit: "day", every: 1, separator: "week", length: DAY},
        {unit: "week", every: 1, separator: "month", length: 7 * DAY},
        {unit: "month", every: 1, separator: "year", length: 30 * DAY},
        {unit: "month", every: 3, separator: "year", length: 90 * DAY},
        {unit: "year", every: 1, separator: "year", length: 365 * DAY},
    ];

    /** How a tick names its own unit: as short as it can be at that zoom level. */
    const TICK_FORMATS: Record<Unit, Intl.DateTimeFormatOptions> = {
        second: {hour: "2-digit", minute: "2-digit", second: "2-digit"},
        minute: {hour: "2-digit", minute: "2-digit"},
        hour: {hour: "2-digit", minute: "2-digit"},
        day: {weekday: "short", day: "numeric"},
        week: {day: "numeric", month: "short"},
        month: {month: "short"},
        year: {year: "numeric"},
    };

    /** How a separator names its section — the context the ticks next to it lack. */
    const SEPARATOR_FORMATS: Record<Unit, Intl.DateTimeFormatOptions> = {
        second: {hour: "2-digit", minute: "2-digit", second: "2-digit"},
        minute: {hour: "2-digit", minute: "2-digit"},
        hour: {hour: "2-digit", minute: "2-digit"},
        day: {weekday: "short", day: "numeric", month: "short"},
        week: {day: "numeric", month: "short"},
        month: {month: "long", year: "numeric"},
        year: {year: "numeric"},
    };

    /** Ticks thinner than this are dropped; labels closer than LABEL_SPACING collide. */
    const MINOR_SPACING = 20;

    /**
     * How long a tick, a separator or a label takes to fade. A whole scale is
     * exchanged at once when a zoom step is crossed, and cutting between the two
     * reads as a flicker.
     *
     * Only the opacity is animated. The positions follow the gesture directly — a
     * tick easing towards where the drag already is would read as lag.
     */
    const FADE = 150;

    /** How far a separator has to stand from the left edge to get its own label. */
    const CONTEXT_SPACING = 60;

    const reducedMotion = new MediaQuery("(prefers-reduced-motion: reduce)");
    let fadeDuration = $derived(reducedMotion.current ? 0 : FADE);

    let trackEl: HTMLDivElement | null = $state(null);
    let width = $state(0);

    let oldestMs = $derived(oldestPoint.getTime());
    let newestMs = $derived(newestPoint.getTime());

    // Falling back to the full history keeps every derivation below valid during the
    // one frame between mounting and the first measurement.
    let startMs = $derived(view?.start.getTime() ?? oldestMs);
    let spanMs = $derived(Math.max(1, (view?.end.getTime() ?? newestMs) - startMs));
    let msPerPixel = $derived(width > 0 ? spanMs / width : 0);

    // The history is the whole world: the window can neither be wider than it nor be
    // moved past either of its ends, so there is no scrolling into empty time. A
    // history shorter than CLOSEST_SPAN is simply shown whole.
    let maxSpan = $derived(Math.max(newestMs - oldestMs, 1));
    let minSpan = $derived(Math.min(CLOSEST_SPAN, maxSpan));

    const clamp = (value: number, low: number, high: number) => Math.min(Math.max(value, low), high);

    /** Publishes a window, clamped to the zoom limits and to the history's ends. */
    function setWindow(nextStart: number, nextSpan: number) {
        const span = clamp(nextSpan, minSpan, maxSpan);
        const start = clamp(nextStart, oldestMs, newestMs - span);
        view = {start: new Date(start), end: new Date(start + span)};
    }

    /**
     * Zooms by [factor] — below 1 zooms in — around [anchor], a 0…1 position across
     * the track. The moment under the anchor stays where it is.
     */
    export function zoomBy(factor: number, anchor = 0.5) {
        const span = clamp(spanMs * factor, minSpan, maxSpan);
        setWindow(startMs + (spanMs - span) * anchor, span);
    }

    /** Moves the window by [deltaMs]; positive scrolls towards newer points. */
    export function panBy(deltaMs: number) {
        setWindow(startMs + deltaMs, spanMs);
    }

    /** Shows exactly [start]…[end], as far as the zoom limits allow. */
    export function showRange(start: Date, end: Date) {
        setWindow(start.getTime(), end.getTime() - start.getTime());
    }

    /** Frames the whole history — the widest the window can get. */
    export function fit() {
        setWindow(oldestMs, maxSpan);
    }

    // Open on the most recent week, or on the whole history if it is shorter: months
    // of points squeezed into one strip say nothing, and what a device did lately is
    // what a reader looks for first. Only ever runs while there is no window — a
    // later history update must not yank the view away from where the user scrolled.
    $effect(() => {
        if (view != null || width === 0) return;

        const span = Math.min(OPENING_SPAN, maxSpan);
        setWindow(newestMs - span, span);
    });

    /** Where a moment sits on the track, in pixels from its left edge. */
    const xOf = (time: number) => (time - startMs) / msPerPixel;

    /** Which 0…1 position across the track an event happened at. */
    function anchorOf(event: {clientX: number}) {
        const rect = trackEl?.getBoundingClientRect();
        if (rect == null || rect.width === 0) return 0.5;
        return clamp((event.clientX - rect.left) / rect.width, 0, 1);
    }

    // Wheel deltas come in three units; only pixels can be turned into a distance.
    const wheelPixels = (delta: number, mode: number) =>
        mode === WheelEvent.DOM_DELTA_LINE ? delta * 16
            : mode === WheelEvent.DOM_DELTA_PAGE ? delta * width
                : delta;

    function onWheel(event: WheelEvent) {
        event.preventDefault();

        // A trackpad pinch arrives as a wheel event with ctrlKey set — that, and a
        // held modifier, are the zoom gestures. Everything else scrolls sideways,
        // whichever axis the gesture came in on, so a two-finger swipe pans.
        if (event.ctrlKey || event.metaKey) {
            // Fingers that spread and travel at the same time report both at once: the
            // spread as deltaY, the travel as deltaX. Zooming and leaving the travel
            // on the floor would drop half of the gesture, so both are applied — the
            // shift is measured before the zoom, at the scale the fingers moved on.
            const shift = wheelPixels(event.deltaX, event.deltaMode) * msPerPixel;
            zoomBy(Math.exp(wheelPixels(event.deltaY, event.deltaMode) * 0.01), anchorOf(event));
            if (shift !== 0) panBy(shift);
            return;
        }

        const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        panBy(wheelPixels(delta, event.deltaMode) * msPerPixel);
    }

    // The listener is attached by hand because it has to be non-passive: only then
    // can it keep a trackpad pinch from zooming the whole page instead.
    $effect(() => {
        const node = trackEl;
        if (node == null) return;

        node.addEventListener("wheel", onWheel, {passive: false});
        return () => node.removeEventListener("wheel", onWheel);
    });

    // Where each pressed pointer currently is. One of them drags the timeline, two
    // of them pinch it; going through pointer events rather than touch events makes
    // mouse, pen and finger the same gesture.
    const pointers = new Map<number, {x: number; y: number}>();

    /** Distance and centre of the two-finger gesture, as of the last move. */
    let pinch: {distance: number; centre: number} | null = null;

    const pinchOf = (points: {x: number; y: number}[]) => ({
        distance: Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y),
        centre: (points[0].x + points[1].x) / 2,
    });

    function onPointerDown(event: PointerEvent) {
        // Capturing keeps the gesture alive when a finger leaves the element, and
        // stops the browser from claiming it as a scroll or a text selection.
        trackEl?.setPointerCapture(event.pointerId);
        pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
        pinch = pointers.size === 2 ? pinchOf([...pointers.values()]) : null;
    }

    function onPointerMove(event: PointerEvent) {
        const previous = pointers.get(event.pointerId);
        if (previous == null) return;

        const current = {x: event.clientX, y: event.clientY};
        pointers.set(event.pointerId, current);

        if (pointers.size >= 2) {
            const next = pinchOf([...pointers.values()].slice(0, 2));
            if (pinch != null && next.distance > 0 && pinch.distance > 0) {
                // Fingers spreading apart mean less time across the same pixels.
                zoomBy(pinch.distance / next.distance, anchorOf({clientX: next.centre}));
                panBy(-(next.centre - pinch.centre) * msPerPixel);
            }
            pinch = next;
            return;
        }

        // Dragging the track to the right pulls earlier moments into view.
        panBy(-(current.x - previous.x) * msPerPixel);
    }

    function onPointerUp(event: PointerEvent) {
        pointers.delete(event.pointerId);
        // Whatever is left has moved on since the pinch started; a fresh baseline is
        // taken on the next move rather than jumping by the difference.
        pinch = null;
    }

    function onKeyDown(event: KeyboardEvent) {
        const handlers: Record<string, () => void> = {
            ArrowLeft: () => panBy(-spanMs / 10),
            ArrowRight: () => panBy(spanMs / 10),
            "+": () => zoomBy(0.8),
            "-": () => zoomBy(1.25),
            Home: () => showRange(oldestPoint, new Date(oldestMs + spanMs)),
            End: () => showRange(new Date(newestMs - spanMs), newestPoint),
        };

        const handler = handlers[event.key];
        if (handler == null) return;

        event.preventDefault();
        handler();
    }

    // The scrollbar covers exactly what the window can reach, which is the history
    // and nothing besides it — so a thumb filling the bar means "everything".
    let space = $derived({start: oldestMs, length: maxSpan});

    let barWidth = $state(0);

    /** The thumb, in pixels along the bar. Never thinner than its two grips. */
    let thumb = $derived.by(() => {
        if (barWidth === 0) return null;

        const scale = barWidth / space.length;
        const width = Math.min(barWidth, Math.max(24, spanMs * scale));
        return {left: clamp((startMs - space.start) * scale, 0, barWidth - width), width};
    });

    /** Which part of the thumb a gesture holds: the bar itself, or one of its ends. */
    type Grip = "window" | "start" | "end";

    /**
     * The window as it was when the gesture started, plus the scale of the bar at
     * that moment. Both are frozen for the whole drag: resizing the window changes
     * how much time the bar covers, and a live scale would make the grip run away
     * from the pointer.
     */
    let drag: {grip: Grip; originX: number; start: number; span: number; msPerPixel: number} | null = null;

    /** Applies [shift] milliseconds of movement to the part of the window [grip] holds. */
    function moveGrip(grip: Grip, from: {start: number; span: number}, shift: number) {
        if (grip === "window") {
            setWindow(from.start + shift, from.span);
            return;
        }

        // Dragging an end resizes the window against its other end, which is what
        // makes the two grips a zoom control rather than a second way to scroll.
        if (grip === "start") {
            const end = from.start + from.span;
            const span = clamp(end - (from.start + shift), minSpan, maxSpan);
            setWindow(end - span, span);
            return;
        }

        setWindow(from.start, clamp(from.span + shift, minSpan, maxSpan));
    }

    function onGripDown(grip: Grip, event: PointerEvent) {
        if (barWidth === 0) return;

        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
        drag = {
            grip,
            originX: event.clientX,
            start: startMs,
            span: spanMs,
            msPerPixel: space.length / barWidth,
        };
    }

    function onGripMove(event: PointerEvent) {
        if (drag == null) return;
        moveGrip(drag.grip, drag, (event.clientX - drag.originX) * drag.msPerPixel);
    }

    function onGripUp() {
        drag = null;
    }

    function onGripKey(grip: Grip, event: KeyboardEvent) {
        const direction = event.key === "ArrowLeft" ? -1 : event.key === "ArrowRight" ? 1 : 0;
        if (direction === 0) return;

        event.preventDefault();
        moveGrip(grip, {start: startMs, span: spanMs}, (direction * spanMs) / 10);
    }

    /**
     * Which weekday a week starts on here — Monday in most of the world, Sunday in
     * the US — so a week separator lands where the reader expects it.
     */
    let weekStart = $derived.by(() => {
        // Read outside the try: a store may only be subscribed to from the top level
        // of the component, which a nested block is not.
        const tag = $locale ?? "en";

        try {
            // Intl counts Monday as 1 and Sunday as 7; getDay() counts Sunday as 0.
            const locale = new Intl.Locale(tag) as Intl.Locale & {getWeekInfo?: () => {firstDay: number}};
            return (locale.getWeekInfo?.().firstDay ?? 1) % 7;
        } catch {
            return 1;
        }
    });

    /** The start of the [unit] × [every] section that contains [time], on the local clock. */
    function floorTo(time: number, unit: Unit, every: number): Date {
        const date = new Date(time);
        date.setMilliseconds(0);
        if (unit === "second") {
            date.setSeconds(Math.floor(date.getSeconds() / every) * every);
            return date;
        }

        date.setSeconds(0);
        if (unit === "minute") {
            date.setMinutes(Math.floor(date.getMinutes() / every) * every);
            return date;
        }

        date.setMinutes(0);
        if (unit === "hour") {
            date.setHours(Math.floor(date.getHours() / every) * every);
            return date;
        }

        date.setHours(0);
        if (unit === "day") return date;
        if (unit === "week") {
            date.setDate(date.getDate() - ((date.getDay() - weekStart + 7) % 7));
            return date;
        }

        date.setDate(1);
        if (unit === "month") {
            date.setMonth(Math.floor(date.getMonth() / every) * every);
            return date;
        }

        date.setMonth(0);
        return date;
    }

    /** The next section after [date]. Going through the setters keeps it on the calendar. */
    function advance(date: Date, unit: Unit, every: number): Date {
        const next = new Date(date);
        if (unit === "second") next.setSeconds(next.getSeconds() + every);
        else if (unit === "minute") next.setMinutes(next.getMinutes() + every);
        else if (unit === "hour") next.setHours(next.getHours() + every);
        else if (unit === "day") next.setDate(next.getDate() + every);
        else if (unit === "week") next.setDate(next.getDate() + 7 * every);
        else if (unit === "month") next.setMonth(next.getMonth() + every);
        else next.setFullYear(next.getFullYear() + every);
        return next;
    }

    /** Every [unit] × [every] boundary inside the window, oldest first. */
    function boundaries(unit: Unit, every: number): number[] {
        if (width === 0 || msPerPixel === 0) return [];

        const result: number[] = [];
        const end = startMs + spanMs;
        let cursor = floorTo(startMs, unit, every);

        // The guard is what keeps a scale that somehow doesn't fit from locking up
        // the tab; at one label per LABEL_SPACING pixels it is never reached.
        for (let guard = 0; guard < 500 && cursor.getTime() <= end; guard++) {
            if (cursor.getTime() >= startMs) result.push(cursor.getTime());
            cursor = advance(cursor, unit, every);
        }
        return result;
    }

    /** The scale whose ticks are far enough apart to be labelled at this zoom. */
    let scale = $derived(SCALES.find((candidate) => candidate.length >= msPerPixel * LABEL_SPACING) ?? SCALES[SCALES.length - 1]);

    /** One step finer, drawn without labels — but only while it stays legible. */
    let subScale = $derived.by(() => {
        const finer = SCALES[SCALES.indexOf(scale) - 1];
        return finer != null && finer.length / msPerPixel >= MINOR_SPACING ? finer : null;
    });

    let tickFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, TICK_FORMATS[scale.unit]));
    let separatorFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, SEPARATOR_FORMATS[scale.separator]));

    let ticks = $derived(boundaries(scale.unit, scale.every).map((time) => ({time, x: xOf(time), label: tickFormat.format(time)})));

    let minorTicks = $derived.by(() => {
        if (subScale == null) return [];

        const major = new Set(ticks.map((tick) => tick.time));
        return boundaries(subScale.unit, subScale.every).filter((time) => !major.has(time)).map((time) => ({time, x: xOf(time)}));
    });

    /**
     * The lines that say which day, month or year the ticks between them belong to.
     * The first entry may be a label without a line: the section the window opens in
     * started before the window did, and is the one the reader needs most.
     */
    let separators = $derived.by(() => {
        if (scale.separator === scale.unit) return [];

        const drawn = boundaries(scale.separator, 1).map((time) => ({
            time,
            x: xOf(time),
            label: separatorFormat.format(time),
            line: true,
        }));

        const leading = floorTo(startMs, scale.separator, 1).getTime();
        if (leading >= startMs || (drawn.length > 0 && drawn[0].x < CONTEXT_SPACING)) return drawn;

        return [{time: leading, x: 0, label: separatorFormat.format(leading), line: false}, ...drawn];
    });

    let momentFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"}));
    let rangeLabel = $derived(view == null ? "" : momentFormat.formatRange(view.start, view.end));
</script>

<div class="flex h-full w-full flex-col gap-1 p-2">
    <div class="flex flex-row items-center justify-between gap-2 px-1">
        <span class="truncate text-xs text-muted-foreground">{rangeLabel}</span>

        <div class="flex shrink-0 flex-row items-center gap-1">
            <button
                    type="button"
                    onclick={() => zoomBy(1.25)}
                    title={$_("timeline.zoom_out")}
                    aria-label={$_("timeline.zoom_out")}
                    class="flex size-7 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-accent"
            >
                <MagnifyingGlassMinusIcon size={16} />
            </button>
            <button
                    type="button"
                    onclick={() => zoomBy(0.8)}
                    title={$_("timeline.zoom_in")}
                    aria-label={$_("timeline.zoom_in")}
                    class="flex size-7 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-accent"
            >
                <MagnifyingGlassPlusIcon size={16} />
            </button>
            <button
                    type="button"
                    onclick={fit}
                    title={$_("timeline.fit")}
                    aria-label={$_("timeline.fit")}
                    class="flex size-7 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-accent"
            >
                <ArrowsOutLineHorizontalIcon size={16} />
            </button>
        </div>
    </div>

    <!-- `touch-none` hands every finger to the pointer handlers above: without it the
         browser would scroll the page instead of the timeline. -->
    <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <!-- A scrollable region is focusable on purpose (ARIA APG): the arrow keys and
         +/- are the keyboard equivalent of the drag and pinch gestures. -->
    <div
            bind:this={trackEl}
            bind:clientWidth={width}
            role="region"
            tabindex="0"
            aria-label={$_("timeline.label")}
            onpointerdown={onPointerDown}
            onpointermove={onPointerMove}
            onpointerup={onPointerUp}
            onpointercancel={onPointerUp}
            onkeydown={onKeyDown}
            class="relative min-h-0 flex-1 cursor-grab touch-none select-none overflow-hidden rounded-2xl bg-card/40 outline-none focus-visible:ring-2 focus-visible:ring-primary/50 active:cursor-grabbing"
    >
        {#each separators as separator (separator.time)}
            {#if separator.line}
                <div
                        class="absolute inset-y-0 w-px bg-border"
                        style:left="{separator.x}px"
                        transition:fade={{duration: fadeDuration}}
                ></div>
            {/if}
            <div
                    class="absolute top-0 whitespace-nowrap pl-1 text-[10px] font-medium text-foreground/70"
                    style:left="{separator.x}px"
                    transition:fade={{duration: fadeDuration}}
            >
                {separator.label}
            </div>
        {/each}

        {#each minorTicks as tick (tick.time)}
            <div
                    class="absolute bottom-0 h-1.5 w-px bg-border/60"
                    style:left="{tick.x}px"
                    transition:fade={{duration: fadeDuration}}
            ></div>
        {/each}

        {#each ticks as tick (tick.time)}
            <div
                    class="absolute bottom-0 h-3 w-px bg-border"
                    style:left="{tick.x}px"
                    transition:fade={{duration: fadeDuration}}
            ></div>
            <div
                    class="absolute bottom-4 -translate-x-1/2 whitespace-nowrap text-[10px] text-muted-foreground"
                    style:left="{tick.x}px"
                    transition:fade={{duration: fadeDuration}}
            >
                {tick.label}
            </div>
        {/each}

        <div class="absolute inset-x-0 bottom-0 h-px bg-border"></div>
    </div>

    <!-- A drawn scrollbar rather than a native one: its thumb is the visible window
         on the reachable stretch of time, and the grips on its ends resize that
         window instead of moving it — dragging them is the third way to zoom. -->
    <div
            bind:clientWidth={barWidth}
            class="relative h-3 w-full shrink-0 overflow-hidden rounded-full bg-card/60"
    >
        {#if thumb != null}
            <div
                    class="absolute inset-y-0 flex flex-row items-stretch"
                    style:left="{thumb.left}px"
                    style:width="{thumb.width}px"
            >
                {@render grip({
                    grip: "start",
                    label: $_("timeline.scrollbar.start"),
                    classes: "w-3 shrink-0 cursor-ew-resize rounded-l-full bg-primary/60 hover:bg-primary/80",
                    min: space.start,
                    max: startMs + spanMs - minSpan,
                    now: startMs,
                })}
                {@render grip({
                    grip: "window",
                    label: $_("timeline.scrollbar.window"),
                    classes: "min-w-0 flex-1 cursor-grab bg-primary/30 hover:bg-primary/40 active:cursor-grabbing",
                    min: space.start,
                    max: space.start + space.length - spanMs,
                    now: startMs,
                })}
                {@render grip({
                    grip: "end",
                    label: $_("timeline.scrollbar.end"),
                    classes: "w-3 shrink-0 cursor-ew-resize rounded-r-full bg-primary/60 hover:bg-primary/80",
                    min: startMs + minSpan,
                    max: space.start + space.length,
                    now: startMs + spanMs,
                })}
            </div>
        {/if}
    </div>
</div>

{#snippet grip(part: {grip: Grip; label: string; classes: string; min: number; max: number; now: number})}
    <div
            role="slider"
            tabindex="0"
            aria-label={part.label}
            aria-valuemin={part.min}
            aria-valuemax={part.max}
            aria-valuenow={part.now}
            aria-valuetext={momentFormat.format(part.now)}
            onpointerdown={(event) => onGripDown(part.grip, event)}
            onpointermove={onGripMove}
            onpointerup={onGripUp}
            onpointercancel={onGripUp}
            onkeydown={(event) => onGripKey(part.grip, event)}
            class="touch-none transition-colors outline-none focus-visible:ring-2 focus-visible:ring-primary/50 {part.classes}"
    ></div>
{/snippet}
