<script lang="ts">
    import {BellRingingIcon, CheckIcon, CircleNotchIcon, PhoneCallIcon, PhoneSlashIcon, XIcon} from "phosphor-svelte";
    import {pingDevice} from "$lib/api/devices/ping_device";
    import {ringDevice, stopRingDevice} from "$lib/api/devices/ring_device";
    import {DeviceRingSocket} from "$lib/state/ring_socket.svelte";

    let {
        deviceId,
    }: {
        deviceId: string
    } = $props();

    // The ring socket is per-device and only lives while this actions panel is
    // mounted (i.e. while the device's detail view is open). No global socket.
    //
    // The parent hands the id in as `device.id`, and every device update replaces
    // that object. Reading the prop in the effect would tie it to the object and
    // reopen the socket on each update; the derived only changes with the id itself.
    let ringDeviceId = $derived(deviceId);
    let ringSocket = $state<DeviceRingSocket | null>(null);
    $effect(() => {
        const socket = new DeviceRingSocket(ringDeviceId);
        socket.open();
        ringSocket = socket;
        return () => {
            socket.close();
            ringSocket = null;
        };
    });

    type PingState = "idle" | "pending" | "delivered" | "found" | "failed";

    let pingState = $state<PingState>("idle");
    let pingResetTimer: ReturnType<typeof setTimeout> | null = null;

    // The device is the source of truth for whether it rings (via the ring
    // socket). But confirmation is async and can be delayed/missed, so we keep an
    // optimistic intent that immediately drives the toggle — this guarantees the
    // "Stoppen" action is always reachable even if a start-confirmation was lost.
    // The optimistic value is dropped as soon as the confirmed state agrees.
    let confirmedRinging = $derived(ringSocket?.isRinging ?? false);
    let optimisticRinging = $state<boolean | null>(null);
    let displayRinging = $derived(optimisticRinging ?? confirmedRinging);
    let awaitingConfirmation = $derived(optimisticRinging !== null && optimisticRinging !== confirmedRinging);
    let ringFailed = $state(false);
    let ringErrorTimer: ReturnType<typeof setTimeout> | null = null;

    // Once the device confirms what we asked for, stop overriding with intent.
    $effect(() => {
        if (optimisticRinging !== null && confirmedRinging === optimisticRinging) {
            optimisticRinging = null;
        }
    });

    function flashPing(state: Exclude<PingState, "idle" | "pending">) {
        pingState = state;
        if (pingResetTimer != null) clearTimeout(pingResetTimer);
        pingResetTimer = setTimeout(() => (pingState = "idle"), 2500);
    }

    async function handlePing() {
        if (pingState === "pending") return;
        if (pingResetTimer != null) clearTimeout(pingResetTimer);
        pingState = "pending";

        const result = await pingDevice(deviceId);
        if (result.type === "success") {
            flashPing(result.hasDeliveredNotification ? "delivered" : "found");
        } else {
            flashPing("failed");
        }
    }

    async function handleRing() {
        const target = !displayRinging;
        optimisticRinging = target;
        ringFailed = false;

        const result = target ? await ringDevice(deviceId) : await stopRingDevice(deviceId);
        if (result.type !== "success") {
            // The command itself was rejected — revert the optimistic intent.
            optimisticRinging = null;
            ringFailed = true;
            if (ringErrorTimer != null) clearTimeout(ringErrorTimer);
            ringErrorTimer = setTimeout(() => (ringFailed = false), 2500);
        }
    }
</script>

<div class="flex w-full overflow-hidden rounded-xl bg-card">
    <button
            type="button"
            onclick={handlePing}
            disabled={pingState === "pending"}
            class="group relative flex min-w-0 flex-1 cursor-pointer items-center justify-center p-3"
    >
        <div class="absolute inset-0 transition-colors group-hover:bg-muted group-disabled:bg-transparent"></div>

        <div class="relative flex flex-col items-center justify-center gap-1.5 transition-transform group-active:scale-95">
            {#if pingState === "pending"}
                <CircleNotchIcon class="size-5 animate-spin" />
            {:else if pingState === "delivered" || pingState === "found"}
                <CheckIcon class="size-5 text-primary" />
            {:else if pingState === "failed"}
                <XIcon class="size-5 text-destructive" />
            {:else}
                <BellRingingIcon class="size-5" />
            {/if}
        </div>
    </button>

    <div class="my-2 w-px bg-border"></div>

    <button
            type="button"
            onclick={handleRing}
            aria-pressed={displayRinging}
            class="group relative flex min-w-0 flex-1 cursor-pointer items-center justify-center p-3"
    >
        <div
                class="absolute inset-0 transition-colors group-hover:bg-muted"
                class:bg-muted={displayRinging}
        ></div>

        <div class="relative flex flex-col items-center justify-center gap-1.5 transition-transform group-active:scale-95">
            {#if ringFailed}
                <XIcon class="size-5 text-destructive" />
            {:else if awaitingConfirmation}
                <CircleNotchIcon class="size-5 animate-spin" />
            {:else if displayRinging}
                <PhoneSlashIcon class="size-5 text-primary" />
            {:else}
                <PhoneCallIcon class="size-5" />
            {/if}
        </div>
    </button>
</div>
