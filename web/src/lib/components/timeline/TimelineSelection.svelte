<script lang="ts">
    import {_} from "svelte-i18n";
    import type {DragModifiers} from "./timeline_gestures";
    import type {TimelineRange} from "./timeline_window";

    let {
        range,
        start,
        end,
        width,
        onresize,
    }: {
        /** The marked stretch. */
        range: TimelineRange;
        /** The window it is marked in, as epoch milliseconds. */
        start: number;
        end: number;
        /** How wide that window is drawn. */
        width: number;
        /**
         * An end was dragged to [anchor], a 0…1 position across the window. Where
         * that lands — and whether it snaps — is the caller's call, which is what
         * keeps the marks it snaps to out of here.
         */
        onresize: (edge: Edge, anchor: number, modifiers: DragModifiers) => void;
    } = $props();

    /** Which end of the selection a gesture holds. */
    type Edge = "start" | "end";

    /** What one arrow key moves an end by, as a share of the window. */
    const NUDGE = 0.01;

    let msPerPixel = $derived(width > 0 ? (end - start) / width : 0);

    const xOf = (time: number) => (msPerPixel > 0 ? (time - start) / msPerPixel : 0);

    // Clipped to the track: a selection reaching past the window would otherwise be
    // drawn kilometres wide, and its handle would sit somewhere off screen.
    let box = $derived({
        left: Math.max(0, xOf(range.start.getTime())),
        right: Math.min(width, xOf(range.end.getTime())),
    });

    /** Whether an end is inside the window, and so has a handle to grab. */
    let visible = $derived({
        start: xOf(range.start.getTime()) >= 0,
        end: xOf(range.end.getTime()) <= width,
    });

    const anchorOf = (clientX: number, node: HTMLElement) => {
        const rect = node.closest("[data-timeline-track]")?.getBoundingClientRect();
        if (rect == null || rect.width === 0) return 0.5;
        return Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1);
    };

    let held: Edge | null = null;

    function onHandleDown(edge: Edge, event: PointerEvent) {
        // The track sweeps out a new selection on pointerdown; this one is meant for
        // the end being grabbed, so it stops there.
        event.stopPropagation();
        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
        held = edge;
    }

    function onHandleMove(event: PointerEvent) {
        if (held == null) return;
        event.stopPropagation();
        onresize(held, anchorOf(event.clientX, event.currentTarget as HTMLElement), event);
    }

    function onHandleUp(event: PointerEvent) {
        event.stopPropagation();
        held = null;
    }

    function onHandleKey(edge: Edge, event: KeyboardEvent) {
        const direction = event.key === "ArrowLeft" ? -1 : event.key === "ArrowRight" ? 1 : 0;
        if (direction === 0) return;

        event.preventDefault();
        const time = edge === "start" ? range.start.getTime() : range.end.getTime();
        const anchor = (xOf(time) + direction * NUDGE * width) / width;
        // Nudging is aiming already, so it is never pulled somewhere else on top.
        onresize(edge, anchor, {ctrlKey: true, metaKey: false, shiftKey: event.shiftKey});
    }
</script>

<!-- The marked range: everything outside it is dimmed rather than the range being
     painted over, so the axis underneath stays readable where it matters. -->
<div class="pointer-events-none absolute inset-y-0 left-0 bg-background/50" style:width="{box.left}px"></div>
<div class="pointer-events-none absolute inset-y-0 right-0 bg-background/50" style:width="{Math.max(0, width - box.right)}px"></div>

<!-- Amber rather than the theme's `--primary`: that token is near-black in light
     mode and near-white in dark, and the map draws the same marked range. -->
<div
        class="pointer-events-none absolute inset-y-0 border-x border-amber-500 bg-amber-500/15"
        style:left="{box.left}px"
        style:width="{Math.max(0, box.right - box.left)}px"
></div>

{#each [{edge: "start" as Edge, x: box.left, label: $_("timeline.selection.start"), time: range.start}, {edge: "end" as Edge, x: box.right, label: $_("timeline.selection.end"), time: range.end}] as handle (handle.edge)}
    {#if visible[handle.edge]}
        <div
                role="slider"
                tabindex="0"
                aria-label={handle.label}
                aria-valuemin={start}
                aria-valuemax={end}
                aria-valuenow={handle.time.getTime()}
                aria-valuetext={handle.time.toLocaleString()}
                onpointerdown={(event) => onHandleDown(handle.edge, event)}
                onpointermove={onHandleMove}
                onpointerup={onHandleUp}
                onpointercancel={onHandleUp}
                onkeydown={(event) => onHandleKey(handle.edge, event)}
                class="absolute inset-y-0 w-3 -translate-x-1/2 cursor-ew-resize touch-none outline-none focus-visible:ring-2 focus-visible:ring-amber-500/50"
                style:left="{handle.x}px"
        >
            <!-- The grab area is wider than the mark, so the end can be caught with a
                 finger without the line having to be thick. -->
            <div class="mx-auto h-full w-1 rounded-full bg-amber-500"></div>
        </div>
    {/if}
{/each}
