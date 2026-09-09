<script lang="ts">
    import {page} from "$app/state";
    import {webappSocket} from "$lib/state/webapp_socket.svelte";
    import {claimCameraTarget} from "$lib/state/map_camera.svelte";
    import DeviceDetails, { type HistoryState } from "$lib/app/devices/DeviceDetails.svelte";
    import DeviceHeader from "$lib/app/devices/DeviceHeader.svelte";
    import {ShareSubscription, shareOriginBase} from "$lib/state/share_socket.svelte";
    import {loadHistory} from "$lib/state/history.svelte";
    import {claimMapTrail} from "$lib/state/map_trail.svelte";
    import {claimMapOverlay} from "$lib/state/map_overlay.svelte";
    import TrailLegend from "$lib/app/shell/map/TrailLegend.svelte";
    import {Timeline, type TimelineRange, type TimelineView} from "$lib/components/timeline";
    import {_} from "svelte-i18n";

    let shareId = $derived(page.params.shareId);
    // The share's origin homeserver. Absent for same-server shares → current origin.
    let homeserver = $derived(page.url.searchParams.get("homeserver") ?? "");
    let isForeign = $derived(homeserver !== "");

    // The history this share is allowed to see, read once on open straight from
    // the origin homeserver. How far back it reaches is the share's decision.
    let history = loadHistory(() => (shareId ? {kind: "share", shareId, homeserver} : null));

    // Foreign shares have no webapp socket on this origin, so subscribe to their
    // host's (persistent, per-host) share socket. Same-server shares are NOT
    // handled here — they already ride the always-on webapp socket below.
    let subscription = $state<ShareSubscription | null>(null);
    $effect(() => {
        if (!isForeign ||!shareId) return;
        const sub = new ShareSubscription(homeserver, shareId);
        sub.open();
        subscription = sub;
        return () => {
            sub.close();
            subscription = null;
        };
    });

    // Unified view of the share regardless of origin. `undefined` = still loading,
    // `null` = not found. Same-server data comes from the persistent webapp socket
    // (already warm from the start page → no per-view socket, no loading flash).
    let share = $derived.by(() => {
        if (isForeign) {
            const snapshot = subscription?.snapshot;
            if (snapshot == null) return snapshot; // undefined (loading) or null (gone)
            return {
                ...snapshot,
                // A foreign homeserver that does not report presence is taken as
                // online — see ShareSnapshot.is_online.
                is_online: snapshot.is_online ?? true,
                base: shareOriginBase(homeserver),
                homeserver,
            };
        }

        const local = webappSocket.shares.find((s) => s.id === shareId);
        if (local != null) {
            return {
                name: local.name,
                manufacturer: local.manufacturer,
                model: local.model,
                device_friendly_name: local.device_friendly_name,
                owner_username: local.owner_username,
                last_location: local.last_location,
                battery: local.battery,
                is_online: local.is_online,
                base: "",
                homeserver: "",
            };
        }
        // Absent from a connected socket → genuinely gone; otherwise still loading.
        return webappSocket.connected ? null : undefined;
    });

    // Camera and trail are claimed for the page as a whole, not per effect run: while
    // a navigation slides, this page and the one being opened are alive at the same
    // time, and only the claim keeps this one from taking the map back on teardown.
    const cameraTarget = claimCameraTarget();
    const mapTrail = claimMapTrail();
    const mapOverlay = claimMapOverlay();

    // Hand the camera to the detail scope (and highlight the pin, present for
    // same-server shares) while the page is open.
    $effect(() => {
        cameraTarget.set(shareId ?? null);
        return () => cameraTarget.release();
    });

    // Draw the history as a line on the map while the page is open.
    $effect(() => {
        mapTrail.set(history.points, shareId ? `share:${shareId}` : null);
        return () => mapTrail.release();
    });

    // A share is read the same way a device is: the same strip beside the map, the
    // same window and marked range, and the same line answering both. What a share
    // hands out is less — how far its history reaches is its owner's decision — but
    // that is a matter of the points, not of how they are read.
    $effect(() => {
        mapOverlay.set(timeline);
        return () => mapOverlay.release();
    });

    let timelineView = $state<TimelineView | null>(null);
    let timelineSelection = $state<TimelineRange | null>(null);

    $effect(() => {
        mapTrail.focus(timelineView, timelineSelection);
    });

    let historyState: HistoryState = $derived.by(() => {
        if (!share || !history) return {type: "loading"}
        if (history.historySeconds === 0) return {type: "not-available"}
        return {type: "available", state: history}
    })
</script>

<div class="flex flex-col h-full gap-2 overflow-y-auto scroll-thin pt-8">
    <DeviceHeader shareId={shareId} homeserver={homeserver} />

    {#if share}
        <div class="flex flex-col gap-2 px-4">
            <DeviceDetails
                    share={share}
                    history={historyState}
            />
        </div>
    {:else if share === null}
        <p class="px-2 mt-4 text-sm text-muted-foreground">{$_("shares.not_found")}</p>
    {:else}
        <p class="px-2 mt-4 text-sm text-muted-foreground">{$_("common.loading")}</p>
    {/if}
</div>

<!-- Nothing to lay a timeline over until the history has arrived, and the two ends
     below would read past the end of an empty list. -->
{#snippet timeline()}
    {#if history.points.length > 0}
        <div class="pointer-events-auto rounded-3xl border border-border bg-accent/65 text-card-foreground shadow-2xl backdrop-blur-lg h-48">
            <Timeline
                    oldestPoint={new Date(history.points[0].timestamp)}
                    newestPoint={new Date(history.points[history.points.length - 1].timestamp)}
                    bind:view={timelineView}
                    bind:selection={timelineSelection}
                    onhover={(at) => mapTrail.hover(at)}
                    actions={legend}
            />
        </div>
    {/if}
{/snippet}

{#snippet legend()}
    <TrailLegend marked={timelineSelection != null} />
{/snippet}
