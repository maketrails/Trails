<script lang="ts">
    import {MediaQuery} from "svelte/reactivity";
    import {_} from "svelte-i18n";
    import {MOVEMENT_COLORS, type MovementType} from "$lib/app/movements";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";
    import {trailLineColors} from "./trail_features";
    import {heatmapColors} from "./heatmap_features";

    let {
        /** Whether a range is marked. Its entry is left out while there is none to explain. */
        marked = false,
        /** How the history is drawn: the line's colours, the heat's scale, or nothing at all. */
        mode = "trail",
    }: {marked?: boolean; mode?: TrailDisplayMode} = $props();

    // Read straight from what the map paints with, including how it turns with the
    // theme, so the two cannot say different things.
    const darkMode = new MediaQuery("(prefers-color-scheme: dark)");
    let colors = $derived(trailLineColors(darkMode.current));

    // The heat's scale as a CSS gradient, from the first visible stop on: density 0 is
    // transparent on the map, and a swatch fading into nothing would read as empty.
    let heatGradient = $derived(
        `linear-gradient(to right, ${heatmapColors(darkMode.current).slice(1).map(([, color]) => color).join(", ")})`,
    );

    const MOVEMENTS = Object.keys(MOVEMENT_COLORS) as MovementType[];
</script>

<!-- What the colours on the map mean. For the line: the track itself, the ways of
     moving it is coloured by, and the outline of a marked range, each swatch drawn the
     way the line is. For the heat: its scale, from little time spent to a lot. With
     nothing drawn there is nothing to explain. -->
{#if mode === "trail"}
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
{:else if mode === "heatmap"}
    <div
            role="img"
            aria-label={$_("timeline.legend.heatmap.label")}
            class="flex min-w-0 flex-row items-center gap-1.5 overflow-hidden"
    >
        <span class="whitespace-nowrap text-[11px] text-muted-foreground">{$_("timeline.legend.heatmap.less")}</span>
        <span class="h-2.5 w-16 shrink-0 rounded-full" style:background-image={heatGradient} aria-hidden="true"></span>
        <span class="whitespace-nowrap text-[11px] text-muted-foreground">{$_("timeline.legend.heatmap.more")}</span>
    </div>
{/if}

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
