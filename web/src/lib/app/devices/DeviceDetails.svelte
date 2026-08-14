<script lang="ts" module>
    import type {HistoryLoad} from "$lib/state/history.svelte";

    export type HistoryState =
        { type: "loading" } |
        { type: "not-available" } |
        { type: "available", state: HistoryLoad };
</script>

<script lang="ts">
    import type {Snippet} from "svelte";
    import {ClockCounterClockwiseIcon, ClockIcon, DeviceMobileIcon, MapPinIcon} from "phosphor-svelte";
    import BatteryIcon from "$lib/components/BatteryIcon.svelte";
    import type {Battery, LastLocation} from "$lib/state/webapp_socket.svelte";
    import {shareOwnerHandle} from "$lib/state/share_socket.svelte";
    import {currentUser} from "$lib/state/current_user";
    import dayjs from "$lib/dayjs";
    import {_} from "svelte-i18n";
    import {locationHistoryLabel} from "$lib/app/emitted-shares/location_history";

    // A shared device, as assembled by the share detail page. Carries everything
    // needed to render the header, so callers hand over the object rather than
    // crafting a title/subtitle themselves.
    interface Share {
        manufacturer: string;
        model: string;
        device_friendly_name: string;
        owner_username: string;
        last_location: LastLocation | null;
        battery: Battery | null;
        // URL base of the share's origin homeserver ("" = current origin).
        base: string;
        // The origin homeserver itself ("" = this server), shown as part of the
        // owner's handle. Kept apart from [base], which is a URL and not a name.
        homeserver: string;
    }

    let {
        share,
        imageUrl,
        title,
        subtitle = null,
        lastLocation,
        battery = null,
        history,
        actions,
    }: {
        // When given, drives the whole header (title = friendly name, subtitle =
        // "von <owner>") and takes precedence over the explicit props below.
        share?: Share;
        imageUrl?: string | null;
        title?: string;
        // Optional secondary line under the title (e.g. manufacturer + model).
        subtitle?: string | null;
        lastLocation?: LastLocation | null;
        battery?: Battery | null;
        history: HistoryState;
        // Optional content placed beside the image, below the title/subtitle
        // (e.g. the ping/ring actions for the user's own devices).
        actions?: Snippet;
    } = $props();

    let resolvedImageUrl = $derived(
        share ? `${share.base}/api/v1/devices/image/${share.manufacturer}-${share.model}` : imageUrl ?? null
    );
    let resolvedTitle = $derived(share ? share.device_friendly_name : title ?? "");

    // A same-server share reports no homeserver of its own — its origin is this
    // server, i.e. the homeserver of the account looking at it.
    let ownerHandle = $derived(
        share == null
            ? ""
            : shareOwnerHandle(share.owner_username, share.homeserver || ($currentUser?.homeserver ?? ""))
    );

    let resolvedSubtitle = $derived(
        share ? $_("shares.owner", {values: {owner: ownerHandle}}) : subtitle
    );
    let resolvedLastLocation = $derived(share ? share.last_location : lastLocation ?? null);
    let resolvedBattery = $derived(share ? share.battery : battery);

    let imageAvailable = $state(true);

    function handleImageError() {
        imageAvailable = false;
    }

    // Reset the image fallback when the source changes (e.g. navigating between
    // two detail views that reuse this component).
    $effect(() => {
        resolvedImageUrl;
        imageAvailable = true;
    });

    const TWO_MINUTES_MS = 2 * 60 * 1000;

    let placeText = $derived.by(() => {
        const location = resolvedLastLocation;
        if (location == null) return $_("devices.never_seen");

        const address = location.address;
        return address != null
            ? [
                [address.road, address.house_number].filter(Boolean).join(" "),
                address.city,
                address.country,
            ].filter(Boolean).join(", ") || address.label
            : `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`;
    });

    let timeText = $derived.by(() => {
        const location = resolvedLastLocation;
        if (location == null) return null;
        if (Date.now() - location.found_at < TWO_MINUTES_MS) return $_("devices.just_now");
        return dayjs(location.found_at).fromNow();
    });
</script>

<div class="flex flex-col gap-2">
    <div class="flex flex-row items-center gap-4 mt-4">
        <div class="size-20 shrink-0 flex items-center justify-center">
            {#if imageAvailable && resolvedImageUrl}
                <img
                        src={resolvedImageUrl}
                        alt={resolvedTitle}
                        class="object-contain w-full h-full"
                        onerror={handleImageError}
                />
            {:else}
                <DeviceMobileIcon class="size-10 text-muted-foreground" />
            {/if}
        </div>

        <div class="flex flex-col min-w-0">
            <span class="text-lg font-semibold truncate leading-tight">{resolvedTitle}</span>

            {#if resolvedSubtitle}
                <span class="text-sm font-light text-muted-foreground truncate">{resolvedSubtitle}</span>
            {/if}

            {#if actions}
                {@render actions()}
            {/if}
        </div>
    </div>

    <div class="grid grid-cols-2 items-center gap-1 px-1 mt-2">
        {#if timeText}
            <div class="flex flex-row items-center gap-1.5 text-sm">
                <ClockIcon class="w-lh h-lh" />
                <span class="font-medium text-muted-foreground truncate">{timeText}</span>
            </div>
        {/if}

        {#if resolvedBattery}
            <div class="flex flex-row items-center gap-1.5 text-sm">
                <BatteryIcon
                        height={16}
                        width={10}
                        isCharging={resolvedBattery.is_charging}
                        percentage={resolvedBattery.percentage}
                        emptyColor="color-mix(in oklab, var(--color-foreground) 18%, transparent)"
                />
                <span class="font-medium text-muted-foreground truncate">
                    {$_(resolvedBattery.is_charging ? "battery.level.charging" : "battery.level.not_charging", {
                        values: {percentage: resolvedBattery.percentage},
                    })}
                </span>
            </div>
        {/if}

        <div class="flex flex-row items-center gap-1.5 text-sm col-span-2">
            <MapPinIcon class="w-lh h-lh" />
            <span class="font-medium text-muted-foreground truncate">{placeText}</span>
        </div>

        <div class="flex flex-row items-center gap-1.5 text-sm">
            <ClockCounterClockwiseIcon />
            <span class="font-medium text-muted-foreground truncate">
                {#if history.type === "loading"}
                    {$_("common.loading")}
                {:else if history.type === "not-available"}
                    {$_("devices.history_unavailable")}
                {:else if history.type === "available"}
                    {#if history.state.historySeconds === null}
                        {$_("history.preset.full")}
                    {:else}
                        {locationHistoryLabel(history.state.historySeconds)}
                    {/if}
                {/if}
            </span>
        </div>
    </div>
</div>