<script lang="ts">
    import './layout.css';
    import favicon from '$lib/assets/favicon.svg';
    import MapComponent from "$lib/app/shell/map/MapComponent.svelte";
    import {currentUser, authInitialized, updateUser} from "$lib/state/current_user";
    import {onMount} from "svelte";
    import UserIcon from "$lib/app/shell/UserIcon.svelte";
    import {isReconnecting, startWebappSocket} from "$lib/state/webapp_socket.svelte";
    import {startForeignShareSync} from "$lib/state/share_socket.svelte";
    import {setContentRect, setOverlayRect, type ContentRect} from "$lib/state/map_camera.svelte";
    import {mapOverlay} from "$lib/state/map_overlay.svelte";
    import CameraModeSwitch from "$lib/app/shell/map/CameraModeSwitch.svelte";
    import {CircleNotchIcon} from "phosphor-svelte";
    import {page} from "$app/state";
    import {beforeNavigate} from "$app/navigation";
    import {cubicOut} from "svelte/easing";
    import {locale} from "svelte-i18n";
    import { fly } from 'svelte/transition';

    let { children } = $props();

    // `<html lang>` is server-rendered from Accept-Language (see hooks.server.ts);
    // the browser may prefer a different language, so keep the attribute honest
    // once the client-side locale is in place.
    $effect(() => {
        const active = $locale;
        if (active) document.documentElement.lang = active;
    });

    let cardEl: HTMLElement | null = $state(null);
    let stripEl: HTMLElement | null = $state(null);

    // Direction of the last client-side navigation, used to drive the
    // iOS-style push/pop slide: deeper routes push forward, shallower pop back.
    let direction: "forward" | "back" = $state("forward");
    let reducedMotion = $state(false);

    const routeDepth = (pathname: string) => pathname.split("/").filter(Boolean).length;

    beforeNavigate((nav) => {
        if (!nav.from || !nav.to) return;
        direction = routeDepth(nav.to.url.pathname) < routeDepth(nav.from.url.pathname)
            ? "back"
            : "forward";
    });

    // iOS-style stack slide. The page on top gets the full slide; the one
    // underneath gets a subtle parallax. Two things make it feel like a real
    // stack instead of two transparent sheets sliding past each other:
    //   1. z-index is driven here so the top page always covers the underneath
    //      one — DOM order alone would wrongly float the incoming page above the
    //      outgoing one when popping.
    //   2. the underneath page is clipped to exactly the region the top page
    //      does NOT cover, so its content never shows through the (translucent)
    //      top page. Both transitions run in lockstep (same duration/easing/
    //      start), so at progress `t` the top page's leading edge sits at
    //      `t * 100%` — which is all the underneath page needs to clip itself.
    const PARALLAX = 30;

    const stack = (node: HTMLElement, edge: "enter" | "leave") => {
        const onTop = direction === "forward" ? edge === "enter" : edge === "leave";
        node.style.zIndex = onTop ? "2" : "1";

        const base = getComputedStyle(node).transform;
        const transform = base === "none" ? "" : base;
        const duration = reducedMotion ? 0 : 320;

        if (onTop) {
            return {
                duration,
                easing: cubicOut,
                css: (t: number) => `transform: ${transform} translateX(${(1 - t) * 100}%);`,
            };
        }

        return {
            duration,
            easing: cubicOut,
            css: (t: number) =>
                `transform: ${transform} translateX(${(1 - t) * -PARALLAX}%);` +
                `clip-path: inset(0 ${(100 - PARALLAX) * (1 - t)}% 0 0);`,
        };
    };

    onMount(() => {
        startWebappSocket();
        startForeignShareSync();
        updateUser();

        const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
        reducedMotion = mq.matches;
        const onChange = () => (reducedMotion = mq.matches);
        mq.addEventListener("change", onChange);
        return () => mq.removeEventListener("change", onChange);
    })

    // Keep the store in sync with what the overlays cover, so the map can inset its
    // viewport padding and never place pins behind them. An element that is not
    // drawn — the strip while it is empty or hidden — measures 0 and is published
    // as "covers nothing" rather than as a box at the origin.
    const trackRect = (el: HTMLElement | null, publish: (rect: ContentRect | null) => void) => {
        if (el == null) return;

        const update = () => {
            const rect = el.getBoundingClientRect();
            publish(
                rect.width === 0 || rect.height === 0
                    ? null
                    : { top: rect.top, left: rect.left, width: rect.width, height: rect.height },
            );
        };
        update();

        const observer = new ResizeObserver(update);
        observer.observe(el);
        window.addEventListener("resize", update);

        return () => {
            observer.disconnect();
            window.removeEventListener("resize", update);
            publish(null);
        };
    };

    $effect(() => trackRect(cardEl, setContentRect));
    $effect(() => trackRect(stripEl, setOverlayRect));
</script>

<svelte:head>
    <link rel="icon" href={favicon} />
    <title>Trails</title>
</svelte:head>

<div class="fixed inset-0 z-0">
    <MapComponent />
</div>

<!-- Everything drawn on top of the map lives in one grid laid over it, rather
     than each overlay positioning itself against the viewport. The columns are
     what used to be the card's own widths, so the card fills the first one and
     the space it leaves is a track a page can draw into (see mapOverlay). Below
     md the card takes the full width and that middle track does not exist, so
     the grid drops to card + controls and the strip stays hidden. -->
<div
        class="pointer-events-none fixed inset-0 z-10 grid grid-cols-[minmax(0,1fr)_auto] grid-rows-[auto_minmax(0,1fr)_auto] gap-4 p-4
           md:grid-cols-[min(50%,25rem)_minmax(0,1fr)_auto]
           lg:grid-cols-[min(33.333%,25rem)_minmax(0,1fr)_auto]
           xl:grid-cols-[25rem_minmax(0,1fr)_auto]"
>
    <main
            bind:this={cardEl}
            class="xl-card pointer-events-auto relative col-start-1 col-end-[-1] row-span-full h-full w-full max-w-100 overflow-hidden rounded-3xl border border-border bg-accent/65 text-card-foreground shadow-2xl backdrop-blur-lg
               md:col-end-2
               xl:mt-auto
               xl:h-[66.666dvh]"
    >
        {#if !$authInitialized}
            <div class="absolute inset-0 flex items-center justify-center">
                <CircleNotchIcon class="size-8 animate-spin text-muted-foreground" />
            </div>
        {:else}
            {#key page.url.pathname}
                <div
                        class="absolute inset-0 overflow-hidden"
                        in:stack={"enter"}
                        out:stack={"leave"}
                >
                    {@render children()}
                </div>
            {/key}
        {/if}

        {#if $isReconnecting}
            <div
                    class="absolute bottom-0 left-0 w-full h-fit flex items-center justify-center gap-2 text-card-foreground text-sm p-4"
                    transition:fly={{ y: 8, duration: 200 }}
            >
                <div class="flex flex-row items-center gap-2 bg-red-700 text-white px-3 py-1 rounded-full">
                    <CircleNotchIcon class="size-3 animate-spin" />
                    <span class="text-sm font-light">Wiederverbinden...</span>
                </div>
            </div>
        {/if}
    </main>

    <!-- The strip a page can fill, between the card and the camera switch. -->
    <div
            bind:this={stripEl}
            class="pointer-events-none relative col-start-2 row-start-3 hidden self-end md:block"
    >
        {#if mapOverlay.content}
            {@render mapOverlay.content()}
        {/if}
    </div>

    {#if $currentUser}
        <!-- The extra padding on small screens is what the icon used to carry on
             top of the overlay inset, so it keeps its distance from the corner. -->
        <div class="pointer-events-auto relative col-start-[-2] col-end-[-1] row-start-1 justify-self-end max-md:p-4">
            <UserIcon />
        </div>

        <div class="pointer-events-auto relative col-start-[-2] col-end-[-1] row-start-3 self-end justify-self-end">
            <CameraModeSwitch />
        </div>
    {/if}
</div>

<style>
    @media (min-width: 1280px) and (max-height: 600px) {
        .xl-card {
            height: calc(100dvh - 2rem);
        }
    }
</style>
