<script lang="ts">
    import {MediaQuery} from "svelte/reactivity";
    import {_} from "svelte-i18n";
    import {trailBandColors, type TrailBand} from "./trail_features";

    let {
        /** Whether a range is marked. Its entry is left out while there is none to explain. */
        marked = false,
    }: {marked?: boolean} = $props();

    // Read straight from what the map paints with, including how it turns with the
    // theme, so the two cannot say different things.
    const darkMode = new MediaQuery("(prefers-color-scheme: dark)");
    let colors = $derived(trailBandColors(darkMode.current));

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

<!-- What the colours on the line mean. Each swatch is drawn the way the line is: a
     coloured core inside an outline. Two of the four are white, and white has no edge
     of its own — on the map the casing gives it one, and here the border does. -->
<ul
        aria-label={$_("timeline.legend.label")}
        class="flex min-w-0 flex-row items-center gap-2.5 overflow-hidden"
>
    {#each entries as entry (entry.band)}
        <li class="flex shrink-0 flex-row items-center gap-1.5">
            <span
                    class="h-2.5 w-6 shrink-0 rounded-full border-2 {entry.band === 'window' ? 'border-foreground' : 'border-foreground/50'}"
                    style:background-color={colors[entry.band]}
                    aria-hidden="true"
            ></span>
            <span class="whitespace-nowrap text-[11px] text-muted-foreground">{$_(entry.label)}</span>
        </li>
    {/each}
</ul>
