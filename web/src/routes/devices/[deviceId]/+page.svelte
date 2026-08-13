<script lang="ts">
    import {page} from "$app/state";
    import {webappSocket} from "$lib/state/webapp_socket.svelte";
    import {claimCameraTarget} from "$lib/state/map_camera.svelte";
    import DeviceActions from "$lib/app/devices/DeviceActions.svelte";
    import DeviceDetails, {type HistoryState} from "$lib/app/devices/DeviceDetails.svelte";
    import DeviceHeader from "$lib/app/devices/DeviceHeader.svelte";
    import DeviceOptimization from "$lib/app/devices/DeviceOptimization.svelte";
    import HistorySourceTabs from "$lib/app/devices/HistorySourceTabs.svelte";
    import type {HistorySource} from "$lib/api/history/history_repository";
    import {loadHistory} from "$lib/state/history.svelte";
    import {claimMapTrail} from "$lib/state/map_trail.svelte";
    import {_} from "svelte-i18n";

    let deviceId = $derived(page.params.deviceId);
    let device = $derived(webappSocket.devices.find((d) => d.id === deviceId) ?? null);

    // Which of the two series the view shows. Switching it reloads the history
    // and with it the line on the map.
    let historySource = $state<HistorySource>("optimized");

    // The device's full location history, read once per source. Owned devices are
    // never limited, so this is everything the server has recorded.
    let history = loadHistory(() => (deviceId ? {kind: "device", deviceId, source: historySource} : null));

    // The ping/ring actions only make sense for the user's own devices. The
    // device list only carries owned devices, so membership doubles as the
    // ownership check should shares ever route to this page.
    let isOwnDevice = $derived(device != null && webappSocket.devices.some((d) => d.id === deviceId));

    // Camera and trail are claimed for the page as a whole, not per effect run: while
    // a navigation slides, this page and the one being opened are alive at the same
    // time, and only the claim keeps this one from taking the map back on teardown.
    const cameraTarget = claimCameraTarget();
    const mapTrail = claimMapTrail();

    // Hand the camera to the detail scope while the page is open, and give it back
    // to the overview on leave.
    $effect(() => {
        cameraTarget.set(deviceId ?? null);
        return () => cameraTarget.release();
    });

    // Draw the history as a line on the map while the page is open. The key names the
    // track, so switching the source animates the new line in while the history
    // arriving in pieces does not.
    $effect(() => {
        mapTrail.set(history.points, deviceId ? `device:${deviceId}:${historySource}` : null);
        return () => mapTrail.release();
    });

    let imageUrl = $derived(device ? `/api/v1/devices/image/${device.manufacturer}-${device.model}` : null);

    let historyState: HistoryState = $derived.by(() => {
        if (!device || !history) return {type: "loading"}
        if (history.historySeconds === 0) return {type: "not-available"}
        return {type: "available", state: history}
    })
</script>

<div class="flex flex-col h-full gap-2 overflow-y-auto scroll-thin pt-8">
    {#if device}
        <DeviceHeader
                device={device}
        />
    {/if}

    {#if device}
        {#snippet deviceActions()}
            <div class="mt-1">
                <DeviceActions deviceId={device.id} />
            </div>
        {/snippet}
        <div class="flex flex-col gap-2 px-4">
            <DeviceDetails
                    imageUrl={imageUrl}
                    title={device.name}
                    subtitle={device.hasCustomName ? device.modelName : null}
                    lastLocation={device.last_location}
                    history={historyState}
                    battery={device.battery}
                    actions={isOwnDevice ? deviceActions : undefined}
            />

            <!-- The optimization is only readable for own devices: a share hands
                 out a track, not the state of the machinery behind it. -->
            {#if isOwnDevice}
                <HistorySourceTabs bind:source={historySource} />
                <DeviceOptimization deviceId={device.id} />
            {/if}
        </div>
    {:else}
        <p class="px-2 mt-4 text-sm text-muted-foreground">{$_("devices.not_found")}</p>
    {/if}
</div>
