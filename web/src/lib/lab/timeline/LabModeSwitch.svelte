<script lang="ts">
    import {EyeSlashIcon, FireIcon, PathIcon} from "phosphor-svelte";
    import MapSegmentedControl, {type SegmentedOption} from "$lib/app/shell/map/MapSegmentedControl.svelte";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";

    /**
     * The production display mode switch, held in local state: the lab must not overwrite
     * the viewer's remembered mode.
     */
    let {mode = $bindable()}: {mode: TrailDisplayMode} = $props();

    const MODES: SegmentedOption<TrailDisplayMode>[] = [
        {value: "none", labelKey: "map.trail_display.modes.none", icon: EyeSlashIcon},
        {value: "trail", labelKey: "map.trail_display.modes.trail", icon: PathIcon},
        {value: "heatmap", labelKey: "map.trail_display.modes.heatmap", icon: FireIcon},
    ];
</script>

<MapSegmentedControl labelKey="map.trail_display.label" options={MODES} value={mode} onselect={(next) => (mode = next)} />
