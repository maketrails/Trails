<script lang="ts">
    import {MediaQuery} from "svelte/reactivity";
    import {fade} from "svelte/transition";
    import {locale} from "svelte-i18n";
    import {axisFor} from "./timeline_scale";

    let {
        start,
        end,
        width,
    }: {
        /** The window to divide, as epoch milliseconds. */
        start: number;
        end: number;
        /** How wide it is drawn. Zero draws nothing, which is the state before measuring. */
        width: number;
    } = $props();

    /**
     * How long a tick, a separator or a label takes to fade. A whole scale is
     * exchanged at once when a zoom step is crossed, and cutting between the two
     * reads as a flicker.
     *
     * Only the opacity is animated. The positions follow the gesture directly — a
     * tick easing towards where the drag already is would read as lag.
     */
    const FADE = 150;

    const reducedMotion = new MediaQuery("(prefers-reduced-motion: reduce)");
    let fadeDuration = $derived(reducedMotion.current ? 0 : FADE);

    let axis = $derived(axisFor({start, end, width, locale: $locale}));

    /** Where a moment sits, in pixels from the left edge. */
    let msPerPixel = $derived(width > 0 ? (end - start) / width : 0);
    const xOf = (time: number) => (time - start) / msPerPixel;
</script>

{#each axis.separators as separator (separator.time)}
    {@const x = separator.leading ? 0 : xOf(separator.time)}
    {#if !separator.leading}
        <div
                class="absolute inset-y-0 w-px bg-border"
                style:left="{x}px"
                transition:fade={{duration: fadeDuration}}
        ></div>
    {/if}
    <div
            class="absolute top-0 whitespace-nowrap pl-1 text-[10px] font-medium text-foreground/70"
            style:left="{x}px"
            transition:fade={{duration: fadeDuration}}
    >
        {separator.label}
    </div>
{/each}

{#each axis.minorTicks as time (time)}
    <div
            class="absolute bottom-0 h-1.5 w-px bg-border/60"
            style:left="{xOf(time)}px"
            transition:fade={{duration: fadeDuration}}
    ></div>
{/each}

{#each axis.ticks as tick (tick.time)}
    <div
            class="absolute bottom-0 h-3 w-px bg-border"
            style:left="{xOf(tick.time)}px"
            transition:fade={{duration: fadeDuration}}
    ></div>
    <div
            class="absolute bottom-4 -translate-x-1/2 whitespace-nowrap text-[10px] text-muted-foreground"
            style:left="{xOf(tick.time)}px"
            transition:fade={{duration: fadeDuration}}
    >
        {tick.label}
    </div>
{/each}

<div class="absolute inset-x-0 bottom-0 h-px bg-border"></div>
