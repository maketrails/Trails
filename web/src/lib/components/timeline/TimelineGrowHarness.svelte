<!-- Test harness: a timeline whose newest point can move, as it does while a history
     arrives in pieces, with the bound window written out below it. -->
<script lang="ts">
    import Timeline from "./Timeline.svelte";
    import type {TimelineView} from "./timeline_window";

    let {oldestPoint, newestPoint}: {oldestPoint: Date; newestPoint: Date} = $props();

    let view = $state<TimelineView | null>(null);
</script>

<Timeline {oldestPoint} {newestPoint} bind:view />

<output data-testid="view">{view == null ? "none" : `${view.start.getTime()},${view.end.getTime()}`}</output>
<button
        data-testid="move"
        onclick={() => (view = {start: new Date(oldestPoint.getTime() + 1_000), end: new Date(oldestPoint.getTime() + 61_000)})}
>move</button>
