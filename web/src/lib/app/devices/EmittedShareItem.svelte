<script lang="ts">
    import type {EmittedShare} from "$lib/state/webapp_socket.svelte";
    import {locationHistoryLabel} from "$lib/app/emitted-shares/location_history";
    import {
        BatteryVerticalHighIcon,
        ClockCounterClockwiseIcon,
        DeviceMobileIcon,
        LockIcon,
        UsersIcon,
    } from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {
        share,
    }: {
        share: EmittedShare
    } = $props();

    let imageAvailable = $state(true);

    function handleImageError() {
        imageAvailable = false;
    }

    // Reset the fallback when the row starts showing another device's image.
    $effect(() => {
        share.manufacturer;
        share.model;
        imageAvailable = true;
    });

    // The share's properties, rendered as one row of pills. Location history is
    // always shown (every share has a window, even "current only"), the rest only
    // when they are on.
    let badges = $derived([
        {icon: ClockCounterClockwiseIcon, label: locationHistoryLabel(share.location_history_seconds)},
        ...(share.share_battery_state
            ? [{icon: BatteryVerticalHighIcon, label: $_("emitted_shares.badge.battery")}]
            : []),
        ...(share.allow_multiuse
            ? [{icon: UsersIcon, label: $_("emitted_shares.badge.multiuse")}]
            : []),
        ...(share.is_locked
            ? [{icon: LockIcon, label: $_("emitted_shares.badge.locked")}]
            : []),
    ]);

    let redemptionLabel = $derived(
        $_("emitted_shares.redemptions.label", {values: {count: share.redemption_count}})
    );
</script>

<a class="group flex flex-row gap-3 items-center py-3 pl-2 pr-4 transition-colors duration-100 hover:bg-foreground/10 focus-visible:bg-foreground/10 focus-visible:outline-none"
   href={`/myshare/${share.id}`}>
    <div class="size-10 shrink-0 self-start bg-accent rounded-full flex items-center justify-center">
        {#if imageAvailable}
            <img
                    src={`/api/v1/devices/image/${share.manufacturer}-${share.model}`}
                    alt={share.name}
                    onerror={handleImageError}
                    class="size-full object-contain p-2.5"
            />
        {:else}
            <DeviceMobileIcon class="size-5"/>
        {/if}
    </div>

    <div class="flex flex-col flex-1 min-w-0 gap-1.5">
        <div class="flex flex-col min-w-0">
            <span class="font-medium truncate leading-tight">{share.name}</span>
            <span class="text-xs font-light text-muted-foreground truncate">
                {share.device_display_name}
            </span>
        </div>

        <ul class="flex flex-row flex-wrap gap-1">
            {#each badges as badge (badge.label)}
                <li class="flex flex-row items-center gap-1 min-w-0 rounded-full bg-muted px-2 py-0.5 text-xs leading-4 text-muted-foreground">
                    <badge.icon class="size-3 shrink-0"/>
                    <span class="truncate">{badge.label}</span>
                </li>
            {/each}
        </ul>
    </div>

    <!-- How often the share was redeemed. The number carries the meaning, so the
         word only exists for screen readers and as a tooltip. -->
    <span
            class="shrink-0 self-start inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums
                   {share.redemption_count > 0
                       ? 'bg-primary/10 text-accent-foreground'
                       : 'bg-muted text-muted-foreground'}"
            title={redemptionLabel}
            aria-label={`${share.redemption_count} ${redemptionLabel}`}
    >
        <UsersIcon class="size-3 shrink-0" aria-hidden="true"/>
        {share.redemption_count}
    </span>
</a>
