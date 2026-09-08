<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import type {TimelineWindow} from "./timeline_window";

    let {
        timeline,
    }: {
        /** The window this scrolls and zooms. */
        timeline: TimelineWindow;
    } = $props();

    /** Narrowest the thumb may be drawn, so its two grips stay grabbable. */
    const MIN_THUMB = 24;

    /** Which part of the thumb a gesture holds: the bar itself, or one of its ends. */
    type Grip = "window" | "start" | "end";

    let barWidth = $state(0);

    // The bar covers exactly what the window can reach, which is the bounds and
    // nothing besides them — so a thumb filling the bar means "everything".
    let space = $derived({start: timeline.oldest, length: timeline.maxSpan});

    let thumb = $derived.by(() => {
        if (barWidth === 0) return null;

        const scale = barWidth / space.length;
        const width = Math.min(barWidth, Math.max(MIN_THUMB, timeline.span * scale));
        const left = Math.min(Math.max((timeline.start - space.start) * scale, 0), barWidth - width);
        return {left, width};
    });

    /**
     * The window as it was when the gesture started, plus the scale of the bar at
     * that moment. Both are frozen for the whole drag: resizing the window changes
     * how much time the bar covers, and a live scale would make the grip run away
     * from the pointer.
     */
    let drag: {grip: Grip; originX: number; start: number; span: number; msPerPixel: number} | null = null;

    const clamp = (value: number, low: number, high: number) => Math.min(Math.max(value, low), high);

    /** Applies [shift] milliseconds of movement to the part of the window [grip] holds. */
    function moveGrip(grip: Grip, from: {start: number; span: number}, shift: number) {
        if (grip === "window") {
            timeline.set(from.start + shift, from.span);
            return;
        }

        // Dragging an end resizes the window against its other end, which is what
        // makes the two grips a zoom control rather than a second way to scroll.
        if (grip === "start") {
            const end = from.start + from.span;
            const span = clamp(end - (from.start + shift), timeline.minSpan, timeline.maxSpan);
            timeline.set(end - span, span);
            return;
        }

        timeline.set(from.start, clamp(from.span + shift, timeline.minSpan, timeline.maxSpan));
    }

    function onGripDown(grip: Grip, event: PointerEvent) {
        if (barWidth === 0) return;

        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
        drag = {
            grip,
            originX: event.clientX,
            start: timeline.start,
            span: timeline.span,
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
        moveGrip(grip, {start: timeline.start, span: timeline.span}, (direction * timeline.span) / 10);
    }

    let momentFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"}));
</script>

<!-- A drawn scrollbar rather than a native one: its thumb is the visible window on
     the reachable stretch of time, and the grips on its ends resize that window
     instead of moving it — dragging them zooms. -->
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
                max: timeline.end - timeline.minSpan,
                now: timeline.start,
            })}
            {@render grip({
                grip: "window",
                label: $_("timeline.scrollbar.window"),
                classes: "min-w-0 flex-1 cursor-grab bg-primary/30 hover:bg-primary/40 active:cursor-grabbing",
                min: space.start,
                max: space.start + space.length - timeline.span,
                now: timeline.start,
            })}
            {@render grip({
                grip: "end",
                label: $_("timeline.scrollbar.end"),
                classes: "w-3 shrink-0 cursor-ew-resize rounded-r-full bg-primary/60 hover:bg-primary/80",
                min: timeline.start + timeline.minSpan,
                max: space.start + space.length,
                now: timeline.end,
            })}
        </div>
    {/if}
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
