<script lang="ts">
    import {ArrowsClockwiseIcon, CircleNotchIcon, PathIcon, XIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {
        fetchDeviceOptimization,
        reoptimizeDevice,
        type DeviceOptimization,
    } from "$lib/api/devices/optimization";
    import {webappSocket} from "$lib/state/webapp_socket.svelte";
    import {_, locale} from "svelte-i18n";

    let {
        deviceId,
    }: {
        deviceId: string;
    } = $props();

    let optimization = $state<DeviceOptimization | null>(null);
    let failed = $state(false);
    let starting = $state(false);

    // Progress arrives over the socket; the counts and distances are a full scan
    // on the server and only read on demand.
    let live = $derived(webappSocket.optimizations[deviceId] ?? null);
    let progress = $derived(live?.progress ?? optimization?.progress ?? 0);
    let isRunning = $derived(live?.is_running ?? optimization?.is_running ?? false);

    async function load() {
        const result = await fetchDeviceOptimization(deviceId);
        if (result.type === "success") {
            optimization = result.optimization;
            failed = false;
        } else {
            failed = true;
        }
    }

    $effect(() => {
        deviceId;
        optimization = null;
        load();
    });

    // A finished run changed the counts and distances, so read them again.
    // Deliberately not $state: the effect writes it, and a reactive value it
    // also reads would re-trigger the effect on every run.
    let wasRunning = false;
    $effect(() => {
        if (wasRunning && !isRunning) load();
        wasRunning = isRunning;
    });

    async function handleReoptimize() {
        if (starting || isRunning) return;
        starting = true;
        const result = await reoptimizeDevice(deviceId);
        starting = false;
        if (result.type !== "accepted") failed = true;
    }

    function formatDistance(meters: number): string {
        const numbers = new Intl.NumberFormat($locale ?? undefined, {maximumFractionDigits: meters < 1000 ? 0 : 1});
        return meters < 1000
            ? $_("devices.optimization.meters", {values: {value: numbers.format(meters)}})
            : $_("devices.optimization.kilometers", {values: {value: numbers.format(meters / 1000)}});
    }

    function formatCount(points: number): string {
        return new Intl.NumberFormat($locale ?? undefined).format(points);
    }

    let percentage = $derived(Math.round(progress * 100));
</script>

<div class="flex flex-col gap-3 rounded-xl bg-card p-4">
    <div class="flex flex-row items-center gap-2">
        <PathIcon class="size-5 shrink-0" />
        <span class="text-sm font-semibold">{$_("devices.optimization.title")}</span>
    </div>

    {#if failed && optimization == null}
        <div class="flex flex-row items-center gap-1.5 text-sm text-muted-foreground">
            <XIcon class="size-4 text-destructive" />
            <span>{$_("devices.optimization.unavailable")}</span>
        </div>
    {:else if optimization == null}
        <span class="text-sm text-muted-foreground">{$_("common.loading")}</span>
    {:else}
        <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <div class="flex flex-col">
                <span class="text-muted-foreground">{$_("devices.optimization.optimized_points")}</span>
                <span class="font-medium">{formatCount(optimization.optimized_points)}</span>
            </div>
            <div class="flex flex-col">
                <span class="text-muted-foreground">{$_("devices.optimization.unoptimized_points")}</span>
                <span class="font-medium">{formatCount(optimization.unoptimized_points)}</span>
            </div>
            <div class="flex flex-col">
                <span class="text-muted-foreground">{$_("devices.optimization.optimized_distance")}</span>
                <span class="font-medium">{formatDistance(optimization.optimized_distance_meters)}</span>
            </div>
            <div class="flex flex-col">
                <span class="text-muted-foreground">{$_("devices.optimization.unoptimized_distance")}</span>
                <span class="font-medium">{formatDistance(optimization.unoptimized_distance_meters)}</span>
            </div>
        </div>

        <!-- A finished optimization has nothing to report, so the bar only shows
             while there is something left to do. -->
        {#if percentage < 100 || isRunning}
            <div class="flex flex-col gap-1.5">
                <div class="flex flex-row items-baseline justify-between text-sm">
                    <span class="text-muted-foreground">{$_("devices.optimization.progress")}</span>
                    <span class="font-medium tabular-nums">{percentage}%</span>
                </div>

                <div
                        class="h-1.5 w-full overflow-hidden rounded-full bg-muted"
                        role="progressbar"
                        aria-valuenow={percentage}
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-label={$_("devices.optimization.progress")}
                >
                    <div class="h-full rounded-full bg-primary transition-[width]" style:width="{percentage}%"></div>
                </div>
            </div>
        {/if}

        <Button variant="secondary" class="w-full" onclick={handleReoptimize} disabled={starting || isRunning}>
            {#if starting || isRunning}
                <CircleNotchIcon class="size-4 animate-spin" />
            {:else}
                <ArrowsClockwiseIcon class="size-4" />
            {/if}
            {$_(isRunning ? "devices.optimization.running" : "devices.optimization.reoptimize")}
        </Button>
    {/if}
</div>
