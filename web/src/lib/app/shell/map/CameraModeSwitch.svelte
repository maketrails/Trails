<script lang="ts">
    import {CrosshairIcon, FrameCornersIcon, PathIcon} from "phosphor-svelte";
    import {
        mapCamera,
        setDetailCameraMode,
        setGeneralCameraMode,
        type DetailCameraMode,
        type GeneralCameraMode,
    } from "$lib/state/map_camera.svelte";
    import {_} from "svelte-i18n";
    import {altKeyLabel} from "$lib/platform";
    import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from "$lib/components/ui/tooltip";

    // Which options exist depends on the scope: the overview can only keep every
    // device in view, while a detail view can also frame the target's trail. The
    // two scopes carry independent modes, so the switch reflects whichever one is
    // currently driving the camera. `manual` has no button: moving the map by hand
    // enters it, and then no option is highlighted.
    type Option = { mode: GeneralCameraMode | DetailCameraMode; labelKey: string };

    const GENERAL_OPTIONS: Option[] = [
        {mode: "tracking", labelKey: "map.camera_mode.options.keep_all_in_view"},
    ];

    const DETAIL_OPTIONS: Option[] = [
        {mode: "tracking", labelKey: "map.camera_mode.options.follow_device"},
        {mode: "trail", labelKey: "map.camera_mode.options.keep_trail_in_view"},
    ];

    let isDetail = $derived(mapCamera.scope === "detail");
    let options = $derived(isDetail ? DETAIL_OPTIONS : GENERAL_OPTIONS);
    let activeMode = $derived(isDetail ? mapCamera.detailMode : mapCamera.generalMode);

    // Option/Alt-click on "follow device" keeps the current zoom; both set `altKey`.
    function select(mode: GeneralCameraMode | DetailCameraMode, event: MouseEvent) {
        if (isDetail) setDetailCameraMode(mode as DetailCameraMode, event.altKey);
        else setGeneralCameraMode(mode as GeneralCameraMode);
    }
</script>

<TooltipProvider>
    <div
            role="group"
            aria-label={$_("map.camera_mode.label")}
            class="pointer-events-auto flex flex-col items-center gap-1 rounded-full border border-border bg-accent/65 p-1 text-card-foreground shadow-2xl backdrop-blur-lg"
    >
        {#each options as option (option.mode)}
            {@const active = option.mode === activeMode}
            {@const follow = isDetail && option.mode === "tracking"}
            <Tooltip>
                <TooltipTrigger>
                    {#snippet child({props})}
                        <button
                                {...props}
                                type="button"
                                onclick={(event) => select(option.mode, event)}
                                aria-pressed={active}
                                aria-label={$_(option.labelKey)}
                                class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full transition-colors
                                   {active ? 'bg-primary text-primary-foreground' : 'hover:bg-accent'}"
                        >
                            {#if option.mode === "trail"}
                                <PathIcon size={18} weight={active ? "fill" : "regular"} />
                            {:else if isDetail}
                                <CrosshairIcon size={18} weight={active ? "fill" : "regular"} />
                            {:else}
                                <FrameCornersIcon size={18} weight={active ? "fill" : "regular"} />
                            {/if}
                        </button>
                    {/snippet}
                </TooltipTrigger>
                <TooltipContent side="left" sideOffset={8}>
                    {#if follow}
                        <div class="flex flex-col">
                            <span>{$_(option.labelKey)}</span>
                            <span class="opacity-70">{$_("map.camera_mode.keep_zoom_hint", {values: {key: altKeyLabel()}})}</span>
                        </div>
                    {:else}
                        {$_(option.labelKey)}
                    {/if}
                </TooltipContent>
            </Tooltip>
        {/each}
    </div>
</TooltipProvider>
