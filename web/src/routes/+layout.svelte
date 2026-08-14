<script lang="ts">
    import './layout.css';
    import favicon from '$lib/assets/favicon.svg';
    import MapComponent from "$lib/app/shell/map/MapComponent.svelte";
    import {currentUser, authInitialized, updateUser} from "$lib/state/current_user";
    import {onMount} from "svelte";
    import UserIcon from "$lib/app/shell/UserIcon.svelte";
    import {isReconnecting, startWebappSocket} from "$lib/state/webapp_socket.svelte";
    import {startForeignShareSync} from "$lib/state/share_socket.svelte";
    import {setContentRect} from "$lib/state/map_camera.svelte";
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

    let cardEl: HTMLDivElement | null = $state(null);

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

    // Keep the store in sync with the card's position/size so the map can inset
    // its viewport padding to avoid placing pins behind the card.
    $effect(() => {
        const el = cardEl;
        if (el == null) return;

        const update = () => {
            const rect = el.getBoundingClientRect();
            setContentRect({ top: rect.top, left: rect.left, width: rect.width, height: rect.height });
        };
        update();

        const observer = new ResizeObserver(update);
        observer.observe(el);
        window.addEventListener("resize", update);

        return () => {
            observer.disconnect();
            window.removeEventListener("resize", update);
            setContentRect(null);
        };
    });
</script>

<svelte:head>
    <link rel="icon" href={favicon} />
    <title>Trails</title>
</svelte:head>

<div class="fixed inset-0 z-0">
    <MapComponent />
</div>

<main class="pointer-events-none relative z-10 flex h-full w-full flex-col p-4">
    <div
            bind:this={cardEl}
            class="xl-card pointer-events-auto relative h-full w-full max-w-100 overflow-hidden rounded-3xl border border-border bg-accent/65 text-card-foreground shadow-2xl backdrop-blur-lg
               md:w-1/2
               lg:w-1/3
               xl:mt-auto
               xl:h-[66.666dvh]
               xl:w-100"
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
    </div>
</main>

<style>
    @media (min-width: 1280px) and (max-height: 600px) {
        .xl-card {
            height: calc(100dvh - 2rem);
        }
    }
</style>

{#if $currentUser}
    <div class="fixed right-0 top-0 z-20 max-md:p-8 md:p-4">
        <UserIcon />
    </div>

    <div class="fixed bottom-0 right-0 z-20 p-4">
        <CameraModeSwitch />
    </div>
{/if}