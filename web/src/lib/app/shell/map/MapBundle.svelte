<script lang="ts">
    import {DeviceMobileIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {isTargetOnline} from "$lib/state/presence.svelte";
    import {
        BUNDLE_AVATAR_SIZE,
        BUNDLE_BORDER,
        BUNDLE_GAP,
        BUNDLE_PADDING,
        BUNDLE_TAIL_HEIGHT,
        bundleSize,
    } from "./pin_bundling";
    import {pinPop} from "./pin_transition";

    let {
        items,
    }: {
        // Everything drawn in this pill. Each member keeps its own link, so a bundled
        // device is opened exactly like a lone one — by clicking it.
        items: {id: string; label: string; imageUrl: string; href: string}[];
    } = $props();

    // The sizes come from the same constants the bundling is computed with (see
    // pin_bundling), written as inline styles rather than utility classes: a pill
    // drawn at a different size than the bundling assumed would cover pins it was
    // never asked about.
    let width = $derived(bundleSize(items.length).width);

    // Half-width of the pointer below the pill. Only its height is part of the
    // bundle's box, so this one stays local.
    const TAIL_HALF_WIDTH = 7;

    // Breathing room around each device image, so a member reads as a picture in a
    // row rather than as a disc packed against its neighbour. Keeps it close to the
    // size a lone pin draws its image at.
    const AVATAR_INSET = 4;

    // Members whose device image failed to load; those fall back to a generic icon.
    let failed = $state<Record<string, boolean>>({});

</script>

<!-- Pivoted on the pointer's tip, so the pill grows out of the spot its members
     stand at instead of out of its own middle (see pinPop). -->
<div
        class="relative flex origin-bottom flex-col items-center"
        style:padding-bottom="{BUNDLE_TAIL_HEIGHT}px"
        transition:pinPop
>
    <div
            role="group"
            aria-label={$_("map.bundle.label", {values: {count: items.length}})}
            class="flex flex-row flex-wrap justify-center rounded-full border-solid border-primary/40 bg-background drop-shadow-lg"
            style:width="{width}px"
            style:padding="{BUNDLE_PADDING}px"
            style:gap="{BUNDLE_GAP}px"
            style:border-width="{BUNDLE_BORDER}px"
    >
        {#each items as item (item.id)}
            <a
                    href={item.href}
                    aria-label={item.label}
                    class="block shrink-0 overflow-hidden rounded-full transition-[scale] duration-200 hover:scale-110"
                    style:width="{BUNDLE_AVATAR_SIZE}px"
                    style:height="{BUNDLE_AVATAR_SIZE}px"
                    style:padding="{AVATAR_INSET}px"
            >
                <!-- The offline treatment sits inside the link, so its scale multiplies
                     with the hover bump above instead of being replaced by it. -->
                <div
                        class="size-full transition-[scale,filter] duration-200 {isTargetOnline(item.id) ? 'scale-100 filter-none' : 'scale-90 grayscale'}"
                >
                    {#if failed[item.id]}
                        <DeviceMobileIcon class="size-full p-1 text-primary"/>
                    {:else}
                        <img
                                src={item.imageUrl}
                                alt=""
                                class="size-full object-contain"
                                onerror={() => (failed[item.id] = true)}
                        />
                    {/if}
                </div>
            </a>
        {/each}
    </div>

    <!-- The pointer at the coordinate, drawn as two stacked CSS triangles: the
         lower one is the pill's border colour, the one on top its fill. The fill
         triangle also covers the pill's own bottom border where the two meet, so
         the pointer reads as part of the pill instead of hanging off it. -->
    <div
            class="absolute bottom-0 left-1/2 size-0 -translate-x-1/2 border-solid border-x-transparent border-t-primary/40"
            style:border-left-width="{TAIL_HALF_WIDTH}px"
            style:border-right-width="{TAIL_HALF_WIDTH}px"
            style:border-top-width="{BUNDLE_TAIL_HEIGHT}px"
    ></div>
    <div
            class="absolute left-1/2 size-0 -translate-x-1/2 border-solid border-x-transparent border-t-background"
            style:bottom="{BUNDLE_BORDER + 1}px"
            style:border-left-width="{TAIL_HALF_WIDTH - 1}px"
            style:border-right-width="{TAIL_HALF_WIDTH - 1}px"
            style:border-top-width="{BUNDLE_TAIL_HEIGHT}px"
    ></div>
</div>
