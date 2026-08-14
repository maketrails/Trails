<script lang="ts">
    import type {Share} from "$lib/state/webapp_socket.svelte";
    import {shareOriginBase, shareOwnerHandle} from "$lib/state/share_socket.svelte";
    import {currentUser} from "$lib/state/current_user";
    import {DeviceMobileIcon} from "phosphor-svelte";
    import BatteryIcon from "$lib/components/BatteryIcon.svelte";
    import dayjs from "$lib/dayjs";
    import {_} from "svelte-i18n";

    let {
        share,
        homeserver = "",
    }: {
        share: Share;
        // Origin of a foreign share; empty for same-server. Drives the image
        // origin and the detail link's ?homeserver= param.
        homeserver?: string;
    } = $props();

    let base = $derived(shareOriginBase(homeserver));
    let href = $derived(
        homeserver ? `/share/${share.id}?homeserver=${encodeURIComponent(homeserver)}` : `/share/${share.id}`
    );

    // A same-server share reports no homeserver of its own — its origin is this
    // server, i.e. the homeserver of the account looking at it.
    let ownerHandle = $derived(
        shareOwnerHandle(share.owner_username, homeserver || ($currentUser?.homeserver ?? ""))
    );

    /*
     * An offline device is shown dimmed and its image loses its colour: what the row
     * says is the last thing known about it, not where it is now. The label itself
     * stays at full strength — it is the explanation for the dimming.
     */
    let dimmed = $derived(share.is_online ? "" : "opacity-60");
    let dimmedImage = $derived(share.is_online ? "" : "grayscale opacity-60");

    let imageAvailable = $state(true);

    function handleImageError() {
        imageAvailable = false;
    }

    const TWO_MINUTES_MS = 2 * 60 * 1000;

    let locationText = $derived.by(() => {
        const location = share.last_location;
        if (location == null) return $_("devices.never_seen");

        const address = location.address;
        const place = address != null
            ? [
            [address.road, address.house_number].filter(Boolean).join(" "),
            address.city,
            address.country,
        ].filter(Boolean).join(", ") || address.label
            : `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`;

        const time = Date.now() - location.found_at < TWO_MINUTES_MS
            ? $_("devices.just_now")
            : dayjs(location.found_at).fromNow();

        return $_("devices.place_and_time", {values: {place, time}});
    });
</script>

<!-- Links to the share detail page, keyed by the active-share id (the only
     identifier the client has for a share). -->
<a class="flex flex-row gap-3 items-center transition-colors duration-100 hover:bg-foreground/10 cursor-pointer py-3 pl-2 pr-4 rounded-md"
   href={href}>
    <div class="size-10 bg-accent rounded-full flex items-center justify-center transition-[filter,opacity] duration-200 {dimmedImage}">
        {#if imageAvailable}
            <img
                    src={`${base}/api/v1/devices/image/${share.manufacturer}-${share.model}`}
                    alt={share.name}
                    onerror={handleImageError}
                    class="object-contain p-2.5"
            />
        {:else}
            <DeviceMobileIcon class="size-5"/>
        {/if}
    </div>

    <!-- Title and owner are split the same way as on the share's detail page: the
         device on top, who it comes from underneath. The handle carries the origin
         homeserver, which is the only thing telling two same-named owners apart. -->
    <div class="flex flex-col flex-1 min-w-0">
        <div class="flex flex-row items-center gap-1.5 min-w-0">
            <span class="font-medium truncate leading-tight transition-opacity duration-200 {dimmed}">
                {share.device_friendly_name}
            </span>

            {#if !share.is_online}
                <span class="shrink-0 rounded-full bg-muted px-1.5 py-0.5 text-xs leading-4 text-muted-foreground">
                    {$_("devices.offline")}
                </span>
            {/if}
        </div>

        <span class="text-xs font-light text-muted-foreground truncate transition-opacity duration-200 {dimmed}">
            {$_("shares.owner", {values: {owner: ownerHandle}})}
        </span>
        <span class="text-xs font-light text-muted-foreground truncate transition-opacity duration-200 {dimmed}">
            {locationText}
        </span>
    </div>

    <div class="transition-[filter,opacity] duration-200 {dimmedImage}">
        {#if share.battery}
            <BatteryIcon
                    height={16}
                    width={10}
                    isCharging={share.battery.is_charging}
                    percentage={share.battery.percentage}
                    emptyColor="color-mix(in oklab, var(--color-foreground) 18%, transparent)"
            />
        {/if}
    </div>
</a>
