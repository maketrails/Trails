<script lang="ts" module>
    import {
        fetchDeviceOptimization,
        reoptimizeDevice,
        type DeviceOptimization,
    } from "$lib/api/devices/optimization";

    /**
     * Last known state per device, deliberately outside the component.
     *
     * The device list is re-sent on every incoming snapshot, and an app catching
     * up pushes them in batches of 50 — so this component can be re-created
     * repeatedly while the page stays open. Reading the cache first means a
     * re-created card shows its numbers straight away instead of falling back to
     * "loading" and flickering along with every batch.
     */
    const cache = new Map<string, DeviceOptimization>();
</script>

<script lang="ts">
    import {ArrowsClockwiseIcon, CircleNotchIcon, PathIcon, XIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {OptimizationSocket} from "$lib/state/optimization_socket.svelte";
    import {_, locale} from "svelte-i18n";

    let {
        deviceId,
    }: {
        deviceId: string;
    } = $props();

    let loaded = $state<DeviceOptimization | null>(null);

    /**
     * What the card shows: the freshly read state, or the last known numbers for
     * this device while a read is on its way. A re-created card therefore starts
     * with data instead of flashing "loading".
     */
    let optimization = $derived(loaded ?? cache.get(deviceId) ?? null);
    let failed = $state(false);
    let starting = $state(false);

    // The progress socket lives exactly as long as this card — no global socket
    // for something only shown here.
    let socket = $state<OptimizationSocket | null>(null);
    $effect(() => {
        const opened = new OptimizationSocket();
        opened.open();
        socket = opened;
        return () => {
            opened.close();
            socket = null;
        };
    });

    // Progress arrives over the socket; the counts and distances are a full scan
    // on the server and only read on demand.
    let live = $derived(socket?.progress[deviceId] ?? null);
    let progress = $derived(live?.progress ?? optimization?.progress ?? 0);
    let isRunning = $derived(live?.is_running ?? optimization?.is_running ?? false);

    // Guards that must not be reactive: an effect writes them, and reading a
    // reactive value it writes would make the effect re-trigger itself.
    let inFlight = false;
    let requestedFor: string | null = null;
    let wasRunning = false;

    /**
     * Reads the state without ever blanking what is already on screen — a
     * refetch replaces the numbers, it does not remove them first.
     */
    async function load(device: string) {
        if (inFlight) return;
        inFlight = true;

        const result = await fetchDeviceOptimization(device);

        inFlight = false;

        // The card may have moved on to another device while this was in flight.
        if (device !== deviceId) return;

        if (result.type === "success") {
            cache.set(device, result.optimization);
            loaded = result.optimization;
            failed = false;
        } else {
            failed = true;
        }
    }

    // Only an actual device change starts a load; re-runs for any other reason
    // find `requestedFor` unchanged and do nothing.
    $effect(() => {
        const device = deviceId;
        if (requestedFor === device) return;

        requestedFor = device;
        loaded = null;
        load(device);
    });

    // A finished run changed the counts and distances, so read them again. Reads
    // only the socket, never `optimization` — otherwise the load below would
    // re-trigger this effect through it.
    $effect(() => {
        const running = socket?.progress[deviceId]?.is_running ?? false;

        if (wasRunning && !running) load(deviceId);
        wasRunning = running;
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
