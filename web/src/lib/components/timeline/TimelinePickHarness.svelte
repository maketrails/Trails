<!-- Test harness: a timeline with one block in its lane that stands for [pick], and the
     marked range written out below it. -->
<script lang="ts">
    import Timeline from "./Timeline.svelte";
    import {pickValue, TIMELINE_PICK} from "./timeline_selection";
    import type {TimelineRange} from "./timeline_window";

    let {
        oldestPoint,
        newestPoint,
        pick,
    }: {
        oldestPoint: Date;
        newestPoint: Date;
        pick: {start: number; end: number};
    } = $props();

    let selection = $state<TimelineRange | null>(null);
</script>

<Timeline {oldestPoint} {newestPoint} bind:selection>
    {#snippet lanes()}
        <div data-testid="block" {...{[TIMELINE_PICK]: pickValue(pick.start, pick.end)}}>block</div>
    {/snippet}
</Timeline>

<output data-testid="selection">{selection == null ? "none" : `${selection.start.getTime()},${selection.end.getTime()}`}</output>
