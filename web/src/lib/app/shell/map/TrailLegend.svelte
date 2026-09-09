<script lang="ts">
    import {_} from "svelte-i18n";
    import {TRAIL_BAND_COLORS, type TrailBand} from "./trail_features";

    let {
        /** Whether a range is marked. Its entry is left out while there is none to explain. */
        marked = false,
    }: {marked?: boolean} = $props();

    // Read straight from what the map paints with (see TRAIL_BAND_COLORS), so the two
    // cannot say different things.
    const ENTRIES: {band: TrailBand; label: string}[] = [
        {band: "before", label: "timeline.legend.before"},
        {band: "window", label: "timeline.legend.on_show"},
        {band: "after", label: "timeline.legend.after"},
    ];

    let entries = $derived(
        marked
            ? [...ENTRIES, {band: "selected" as TrailBand, label: "timeline.legend.marked"}]
            : ENTRIES,
    );
</script>

<!-- What the colours on the line mean. The swatches sit on a dark patch because that
     is what they are read against on the map, and two of them are white. -->
<ul
        aria-label={$_("timeline.legend.label")}
        class="flex min-w-0 flex-row items-center gap-2 overflow-hidden rounded-full bg-slate-900/80 px-2 py-1"
>
    {#each entries as entry (entry.band)}
        <li class="flex shrink-0 flex-row items-center gap-1">
            <span
                    class="h-0.5 w-4 rounded-full"
                    style:background-color={TRAIL_BAND_COLORS[entry.band]}
                    aria-hidden="true"
            ></span>
            <span class="whitespace-nowrap text-[10px] text-slate-200">{$_(entry.label)}</span>
        </li>
    {/each}
</ul>
