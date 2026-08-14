<script lang="ts">
    import {DeviceMobileIcon} from "phosphor-svelte";
    import {mapCamera} from "$lib/state/map_camera.svelte";
    import {isTargetOnline} from "$lib/state/presence.svelte";
    import {pinPop} from "./pin_transition";

    let {
        id,
        label,
        imageUrl,
        href = null,
    }: {
        id: string;
        label: string;
        imageUrl: string;
        // When set the pin links somewhere; otherwise it is a plain marker.
        href?: string | null;
    } = $props();

    // Highlighted while its device's detail page is open (see claimCameraTarget).
    let highlighted = $derived(mapCamera.targetId === id);

    /*
     * Read from the state rather than taken as a prop, like [highlighted] above: a pin
     * is mounted imperatively and only ever moved afterwards (see upsertPin), so a
     * prop would keep whatever it was mounted with and never follow the device going
     * offline. [id] is a device id or an active-share id — isTargetOnline resolves
     * both.
     */
    let isOnline = $derived(isTargetOnline(id));

    let imageAvailable = $state(true);

    function handleImageError() {
        imageAvailable = false;
    }

    // Unique per pin so the clip paths of different markers don't collide.
    const clipId = $derived(`map-pin-${id}`);
</script>

{#snippet pin()}
    <!-- Offline layer: the device loses its colour and takes a step back. On its own
         node, and not on the two below, because a scale here has to multiply with the
         focus/hover bump instead of replacing it. -->
    <div
            class="origin-[50%_96.4%] transition-[scale,filter] duration-200 {isOnline ? 'scale-100 filter-none' : 'scale-90 grayscale'}"
    >
    <!-- Outer layer: a gentle, persistent size bump while focused (and on
         hover). Inner layer: the one-shot droplet squash-&-stretch. Keeping
         them separate stops the two transforms from clobbering each other. -->
    <div
            class="origin-[50%_96.4%] transition-[scale] duration-200 group-hover:scale-110 {highlighted ? 'scale-[1.5]' : ''}"
    >
    <div
            class="relative origin-[50%_96.4%]"
            class:wiggle={highlighted}
    >
        <svg
                width="60"
                height="67"
                viewBox="0 0 100 112"
                class="drop-shadow-lg block"
                xmlns="http://www.w3.org/2000/svg"
        >
            <defs>
                <clipPath id={clipId}>
                    <circle cx="50" cy="50" r="46"/>
                </clipPath>
            </defs>

            <!--
                Water-drop shape: a near-full circle head that flows smoothly
                into two sides converging to a point at the coordinate (tip at
                50,108).
            -->
            <path
                    d="M 50 4 C 24.6 4 4 24.6 4 50 C 4 72 17 85 34 94 C 41 98 48 104 50 108 C 52 104 59 98 66 94 C 83 85 96 72 96 50 C 96 24.6 75.4 4 50 4 Z"
                    class="stroke-primary/40 transition-colors {highlighted ? 'fill-primary/15' : 'fill-background'}"
                    stroke-width="2"
                    stroke-linejoin="round"
            />

            {#if imageAvailable}
                <!-- Box inscribed in the head circle so the image is fully visible. -->
                <image
                        href={imageUrl}
                        x="17.5"
                        y="17.5"
                        width="65"
                        height="65"
                        clip-path={`url(#${clipId})`}
                        preserveAspectRatio="xMidYMid meet"
                        onerror={handleImageError}
                />
            {/if}
        </svg>

        {#if !imageAvailable}
            <DeviceMobileIcon
                    class="absolute left-1/2 top-[45%] size-7 -translate-x-1/2 -translate-y-1/2 text-primary"
            />
        {/if}
    </div>
    </div>
    </div>
{/snippet}

<!-- The transition pivots on the tip, like the wiggle above, so the pin grows out
     of the coordinate it marks — see pinPop for why it is on the outermost node. -->
{#if href != null}
    <a {href} aria-label={label} class="group block cursor-pointer origin-[50%_96.4%]" transition:pinPop>
        {@render pin()}
    </a>
{:else}
    <div aria-label={label} class="group block origin-[50%_96.4%]" transition:pinPop>
        {@render pin()}
    </div>
{/if}

<style>
    /* Plays once whenever a pin becomes focused: a damped rotation, as if the
       pin was nudged. Pivots on the tip (origin-[50%_96.4%]) so the anchor
       point stays put. */
    .wiggle {
        animation: pin-wiggle 0.7s ease-in-out;
    }

    @keyframes pin-wiggle {
        0%   { transform: rotate(0deg); }
        15%  { transform: rotate(-20deg); }
        30%  { transform: rotate(15deg); }
        45%  { transform: rotate(-10deg); }
        60%  { transform: rotate(6deg); }
        75%  { transform: rotate(-3deg); }
        100% { transform: rotate(0deg); }
    }

    @media (prefers-reduced-motion: reduce) {
        .wiggle {
            animation: none;
        }
    }
</style>
