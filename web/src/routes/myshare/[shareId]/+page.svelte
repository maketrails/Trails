<script lang="ts">
    import {page} from "$app/state";
    import {
        ArrowLeftIcon,
        BatteryVerticalHighIcon,
        CheckIcon,
        ClipboardIcon,
        ClockCounterClockwiseIcon,
        DeviceMobileIcon,
        LinkIcon,
        LockIcon,
        UsersIcon,
    } from "phosphor-svelte"
    import {webappSocket} from "$lib/state/webapp_socket.svelte";
    import {claimCameraTarget} from "$lib/state/map_camera.svelte";
    import {ShareRepository} from "$lib/api/shares/share_repository";
    import {locationHistoryLabel, locationHistoryOptionsFor} from "$lib/app/emitted-shares/location_history";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {Select, SelectContent, SelectItem, SelectTrigger} from "$lib/components/ui/select";
    import {Field, FieldContent, FieldDescription, FieldTitle} from "$lib/components/ui/field";
    import {Alert, AlertDescription} from "$lib/components/ui/alert";
    import dayjs from "$lib/dayjs";
    import ShareHeader from "$lib/app/emitted-shares/ShareHeader.svelte";
    import { currentUser } from "$lib/state/current_user";
    import InputGroup from "$lib/components/ui/input-group/input-group.svelte";
    import InputGroupInput from "$lib/components/ui/input-group/input-group-input.svelte";
    import InputGroupButton from "$lib/components/ui/input-group/input-group-button.svelte";
    import InputGroupAddon from "$lib/components/ui/input-group/input-group-addon.svelte";
    import {_} from "svelte-i18n";

    let shareId = $derived(page.params.shareId);

    // The emitted shares ride the always-on webapp socket, so the settings and the
    // redemption list are already warm from the start page and stay live.
    // `undefined` = still loading, `null` = genuinely gone.
    let share = $derived.by(() => {
        const emitted = webappSocket.emittedShares.find((s) => s.id === shareId);
        if (emitted != null) return emitted;
        return webappSocket.connected ? null : undefined;
    });

    // Settings the server hasn't confirmed yet. A change is applied locally right
    // away so the control doesn't snap back while the PATCH is in flight; `null`
    // means "no local override, show what the socket says".
    let pendingHistorySeconds = $state<number | null>(null);
    let pendingBatteryState = $state<boolean | null>(null);
    let saveFailed = $state(false);

    let historySeconds = $derived(pendingHistorySeconds ?? share?.location_history_seconds ?? 0);
    let shareBatteryState = $derived(pendingBatteryState ?? share?.share_battery_state ?? false);

    let historyOptions = $derived(locationHistoryOptionsFor(historySeconds));

    // Drop an override as soon as the socket reports the value we sent — from then
    // on the socket is the source of truth again.
    $effect(() => {
        if (share == null) return;
        if (pendingHistorySeconds === share.location_history_seconds) pendingHistorySeconds = null;
        if (pendingBatteryState === share.share_battery_state) pendingBatteryState = null;
    });

    // The camera is claimed for the page as a whole, not per effect run: while a
    // navigation slides, this page and the one being opened are alive at the same
    // time, and only the claim keeps this one from taking the camera back on teardown.
    const cameraTarget = claimCameraTarget();

    // Highlight the shared device's pin and hand the camera to the detail scope
    // while the page is open.
    $effect(() => {
        cameraTarget.set(share?.device_id ?? null);
        return () => cameraTarget.release();
    });

    async function saveHistorySeconds(seconds: number) {
        if (!shareId) return;
        pendingHistorySeconds = seconds;
        saveFailed = false;
        const ok = await ShareRepository.updateEmittedShare(shareId, {location_history_seconds: seconds});
        if (!ok) {
            pendingHistorySeconds = null;
            saveFailed = true;
        }
    }

    async function saveBatteryState(enabled: boolean) {
        if (!shareId) return;
        pendingBatteryState = enabled;
        saveFailed = false;
        const ok = await ShareRepository.updateEmittedShare(shareId, {share_battery_state: enabled});
        if (!ok) {
            pendingBatteryState = null;
            saveFailed = true;
        }
    }

    let imageAvailable = $state(true);

    function handleImageError() {
        imageAvailable = false;
    }

    // Reset the image fallback when the share (and with it the device) changes.
    $effect(() => {
        share?.id;
        imageAvailable = true;
    });

    let shareUrlState = $derived.by(() => {
      if (!share) return null
      if (!$currentUser) return null
      if (share.allow_multiuse || share.active_shares.length === 0) return { type: "ready", uri: "trailsapp://application/" + $currentUser.homeserver + "/share/" + share.id }
    })
    let copyShareUrlReturnStateTimeoutId: number | null = $state(null);
    let copiedShareUrl = $derived(!!copyShareUrlReturnStateTimeoutId);

    function copyShareUrl() {
      if (copyShareUrlReturnStateTimeoutId) {
        clearTimeout(copyShareUrlReturnStateTimeoutId);
      }
      copyShareUrlReturnStateTimeoutId = setTimeout(() => {
        copyShareUrlReturnStateTimeoutId = null;
      }, 3000);

      if (shareUrlState?.type === "ready") navigator.clipboard.writeText(shareUrlState?.uri)
    }
</script>

<div class="flex flex-col h-full gap-2 overflow-y-auto scroll-thin pt-8">
    <ShareHeader share={share}/>

    {#if share}
        <div class="flex flex-col gap-4 px-4 pb-4">
            <div class="flex flex-row items-center gap-4 mt-4">
                <div class="size-20 shrink-0 flex items-center justify-center">
                    {#if imageAvailable}
                        <img
                                src={`/api/v1/devices/image/${share.manufacturer}-${share.model}`}
                                alt={share.name}
                                class="object-contain w-full h-full"
                                onerror={handleImageError}
                        />
                    {:else}
                        <DeviceMobileIcon class="size-10 text-muted-foreground"/>
                    {/if}
                </div>

                <div class="flex flex-col min-w-0">
                    <span class="text-lg font-semibold truncate leading-tight">{share.name}</span>
                    <span class="text-sm font-light text-muted-foreground truncate">{share.device_display_name}</span>
                    <span class="text-xs font-light text-muted-foreground truncate">
                        {$_("emitted_shares.created_at", {values: {when: dayjs(share.created_at).fromNow()}})}
                    </span>
                </div>
            </div>

            <div class="flex flex-row flex-wrap gap-x-3 gap-y-0.5 px-1 text-xs text-muted-foreground">
                <span class="inline-flex items-center gap-1">
                    <UsersIcon class="size-3.5"/>
                    {$_(share.allow_multiuse ? "emitted_shares.multiuse" : "emitted_shares.single_use")}
                </span>
                {#if share.is_locked}
                    <span class="inline-flex items-center gap-1">
                        <LockIcon class="size-3.5"/>
                        {$_("emitted_shares.badge.locked")}
                    </span>
                {/if}
            </div>

            {#if shareUrlState?.type === "ready"}
                <div class="flex flex-col gap-3 rounded-2xl border border-primary/25 bg-primary/5 p-4">
                    <div class="flex flex-row items-center gap-2">
                        <LinkIcon class="size-4 shrink-0 text-primary"/>
                        <span class="text-sm font-semibold text-accent-foreground">{$_("emitted_shares.link.title")}</span>
                    </div>

                    <p class="text-xs text-muted-foreground">
                        {$_(share.allow_multiuse ? "emitted_shares.link.multiuse" : "emitted_shares.link.single_use")}
                    </p>

                    <InputGroup class="bg-background">
                        <InputGroupInput
                                value={shareUrlState.uri}
                                readonly
                                disabled
                        />

                        <InputGroupAddon align="inline-end">
                            <InputGroupButton
                                    variant="link"
                                    size="sm"
                                    onclick={copyShareUrl}
                            >
                                {#if copiedShareUrl}
                                    <CheckIcon />
                                    {$_("common.copied")}
                                {:else}
                                    <ClipboardIcon />
                                    {$_("common.copy")}
                                {/if}
                            </InputGroupButton>
                        </InputGroupAddon>
                    </InputGroup>
                </div>
            {/if}

            {#if saveFailed}
                <Alert variant="destructive">
                    <AlertDescription>
                        {$_("emitted_shares.save_failed")}
                    </AlertDescription>
                </Alert>
            {/if}

            <div class="flex flex-col gap-5">
                <Field>
                    <FieldTitle>
                        <ClockCounterClockwiseIcon class="size-4"/>
                        {$_("emitted_shares.history.title")}
                    </FieldTitle>
                    <Select
                            type="single"
                            value={String(historySeconds)}
                            onValueChange={(value) => saveHistorySeconds(Number(value))}
                    >
                        <SelectTrigger class="w-full">
                            {locationHistoryLabel(historySeconds)}
                        </SelectTrigger>
                        <SelectContent>
                            {#each historyOptions as option (option.seconds)}
                                <SelectItem value={String(option.seconds)} label={option.label}/>
                            {/each}
                        </SelectContent>
                    </Select>
                    <FieldDescription>
                        {$_("emitted_shares.history.description")}
                    </FieldDescription>
                </Field>

                <Field orientation="horizontal">
                    <FieldContent>
                        <FieldTitle>
                            <BatteryVerticalHighIcon class="size-4"/>
                            {$_("emitted_shares.battery.title")}
                        </FieldTitle>
                        <FieldDescription>
                            {$_("emitted_shares.battery.description")}
                        </FieldDescription>
                    </FieldContent>
                    <Checkbox
                            checked={shareBatteryState}
                            onCheckedChange={(checked) => saveBatteryState(checked)}
                    />
                </Field>
            </div>

            <div class="flex flex-col gap-2">
                <h2 class="text-sm font-semibold text-accent-foreground">
                    {$_("emitted_shares.redemptions.heading", {values: {count: share.active_shares.length}})}
                </h2>

                {#if share.active_shares.length === 0}
                    <p class="text-sm text-muted-foreground">{$_("emitted_shares.redemptions.empty")}</p>
                {:else}
                    <div class="flex flex-col rounded-2xl bg-card overflow-hidden">
                        {#each share.active_shares as activeShare, index (activeShare.id)}
                            {#if index > 0}
                                <div class="px-4 w-full h-px">
                                    <div class="w-full h-full bg-border"></div>
                                </div>
                            {/if}
                            <div class="flex flex-row items-center gap-3 py-3 px-4">
                                <LinkIcon class="size-4 shrink-0 text-muted-foreground"/>
                                <div class="flex flex-col min-w-0">
                                    <span class="text-sm truncate">
                                        {$_("emitted_shares.redemptions.redeemed_at", {
                                            values: {when: dayjs(activeShare.created_at).fromNow()},
                                        })}
                                    </span>
                                    <span class="text-xs font-light text-muted-foreground truncate">
                                        <!-- L/LT are dayjs' localized date/time patterns, so this
                                             follows the active locale instead of a fixed format. -->
                                        {dayjs(activeShare.created_at).format("L, LT")}
                                    </span>
                                </div>
                            </div>
                        {/each}
                    </div>
                {/if}
            </div>
        </div>
    {:else if share === null}
        <p class="px-2 mt-4 text-sm text-muted-foreground">{$_("emitted_shares.not_found")}</p>
    {:else}
        <p class="px-2 mt-4 text-sm text-muted-foreground">{$_("common.loading")}</p>
    {/if}
</div>
