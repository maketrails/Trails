<script lang="ts">
    import {CrosshairIcon, FrameCornersIcon, HandIcon, PathIcon} from "phosphor-svelte";
    import {
        mapCamera,
        setDetailCameraMode,
        setGeneralCameraMode,
        type DetailCameraMode,
        type GeneralCameraMode,
    } from "$lib/state/map_camera.svelte";
    import {_} from "svelte-i18n";

    // Which options exist depends on the scope: the overview can only keep every
    // device in view or hand over to the user, while a detail view can also frame
    // the target's trail. The two scopes carry independent modes, so the switch
    // reflects whichever one is currently driving the camera.
    type Option = { mode: GeneralCameraMode | DetailCameraMode; labelKey: string };

    const GENERAL_OPTIONS: Option[] = [
        {mode: "tracking", labelKey: "map.camera_mode.options.keep_all_in_view"},
        {mode: "manual", labelKey: "map.camera_mode.options.move_manually"},
    ];

    const DETAIL_OPTIONS: Option[] = [
        {mode: "tracking", labelKey: "map.camera_mode.options.follow_device"},
        {mode: "trail", labelKey: "map.camera_mode.options.keep_trail_in_view"},
        {mode: "manual", labelKey: "map.camera_mode.options.move_manually"},
    ];

    let isDetail = $derived(mapCamera.scope === "detail");
    let options = $derived(isDetail ? DETAIL_OPTIONS : GENERAL_OPTIONS);
    let activeMode = $derived(isDetail ? mapCamera.detailMode : mapCamera.generalMode);

    // Option/Alt-click on "follow device" keeps the current zoom.
    function select(mode: GeneralCameraMode | DetailCameraMode, event: MouseEvent) {
        if (isDetail) setDetailCameraMode(mode as DetailCameraMode, event.altKey);
        else setGeneralCameraMode(mode as GeneralCameraMode);
    }
</script>

<div
        role="group"
        aria-label={$_("map.camera_mode.label")}
        class="pointer-events-auto flex flex-col items-center gap-1 rounded-full border border-border bg-accent/65 p-1 text-card-foreground shadow-2xl backdrop-blur-lg"
>
    {#each options as option (option.mode)}
        {@const active = option.mode === activeMode}
        <button
                type="button"
                onclick={(event) => select(option.mode, event)}
                aria-pressed={active}
                title={$_(option.labelKey)}
                class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full transition-colors
                   {active ? 'bg-primary text-primary-foreground' : 'hover:bg-accent'}"
        >
            {#if option.mode === "manual"}
                <HandIcon size={18} weight={active ? "fill" : "regular"} />
            {:else if option.mode === "trail"}
                <PathIcon size={18} weight={active ? "fill" : "regular"} />
            {:else if isDetail}
                <CrosshairIcon size={18} weight={active ? "fill" : "regular"} />
            {:else}
                <FrameCornersIcon size={18} weight={active ? "fill" : "regular"} />
            {/if}
        </button>
    {/each}
</div>
