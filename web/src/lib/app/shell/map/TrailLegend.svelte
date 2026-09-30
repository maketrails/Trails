<script lang="ts">
    import {MediaQuery} from "svelte/reactivity";
    import {_} from "svelte-i18n";
    import {MOVEMENT_COLORS, type MovementType} from "$lib/app/movements";
    import {trailLineColors} from "./trail_features";

    let {
        /** Whether a range is marked. Its entry is left out while there is none to explain. */
        marked = false,
    }: {marked?: boolean} = $props();

    // Read straight from what the map paints with, including how it turns with the
    // theme, so the two cannot say different things.
    const darkMode = new MediaQuery("(prefers-color-scheme: dark)");
    let colors = $derived(trailLineColors(darkMode.current));

    const MOVEMENTS = Object.keys(MOVEMENT_COLORS) as MovementType[];
</script>

<!-- What the colours on the line mean: the track itself, the ways of moving it is
     coloured by, and the outline of a marked range. Each swatch is drawn the way the
     line is. -->
<ul
        aria-label={$_("timeline.legend.label")}
        class="flex min-w-0 flex-row items-center gap-2.5 overflow-hidden"
>
    {@render entry(colors.track, $_("timeline.legend.track"))}
    {#each MOVEMENTS as movement (movement)}
        {@render entry(MOVEMENT_COLORS[movement], $_(`history.movements.type.${movement}`))}
    {/each}
    {#if marked}
        {@render entry(colors.track, $_("timeline.legend.marked"), colors.marked)}
    {/if}
</ul>

{#snippet entry(color: string, label: string, outline: string | null = null)}
    <li class="flex shrink-0 flex-row items-center gap-1.5">
        <span
                class="h-2.5 w-6 shrink-0 rounded-full {outline == null ? '' : 'border-2'}"
                style:background-color={color}
                style:border-color={outline}
                aria-hidden="true"
        ></span>
        <span class="whitespace-nowrap text-[11px] text-muted-foreground">{label}</span>
    </li>
{/snippet}
