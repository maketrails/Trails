<script lang="ts">
    import type {Snippet} from "svelte";
    import {EyeSlashIcon, FireIcon, PathIcon, SparkleIcon, WaveformIcon} from "phosphor-svelte";
    import type {HistorySource} from "$lib/api/history/history_repository";
    import {
        setTrailDisplayMode,
        trailDisplayMode,
        type TrailDisplayMode,
    } from "$lib/state/trail_display_mode.svelte";
    import MapSegmentedControl, {type SegmentedOption} from "./MapSegmentedControl.svelte";

    let {
        source = $bindable(),
        end,
    }: {
        /**
         * Which series is drawn, for a view that may choose — an own device. Left out
         * where there is no choice to make (a share), and then no switch is shown.
         */
        source?: HistorySource;
        /** Further controls, laid out at the far end of the bar. */
        end?: Snippet;
    } = $props();

    const MODES: SegmentedOption<TrailDisplayMode>[] = [
        {value: "none", labelKey: "map.trail_display.modes.none", icon: EyeSlashIcon},
        {value: "trail", labelKey: "map.trail_display.modes.trail", icon: PathIcon},
        {value: "heatmap", labelKey: "map.trail_display.modes.heatmap", icon: FireIcon},
    ];

    const SOURCES: SegmentedOption<HistorySource>[] = [
        {value: "optimized", labelKey: "history.source.optimized", icon: SparkleIcon},
        {value: "raw", labelKey: "history.source.raw", icon: WaveformIcon},
    ];
</script>

<!-- The bar above the timeline: how the history is drawn, and from which data. The
     two sit side by side so they read as one choice made in two parts — mode by
     data — and every mode works with either series. Whatever else the bar carries
     goes to the far end; on a strip too narrow for all of it, it wraps. -->
<div class="pointer-events-none flex flex-row flex-wrap items-center gap-2">
    <MapSegmentedControl
            labelKey="map.trail_display.label"
            options={MODES}
            value={trailDisplayMode.mode}
            onselect={setTrailDisplayMode}
    />

    {#if source !== undefined}
        <MapSegmentedControl
                labelKey="history.source.label"
                options={SOURCES}
                value={source}
                onselect={(next) => (source = next)}
        />
    {/if}

    {#if end}
        <div class="ml-auto flex flex-row items-center gap-2">
            {@render end()}
        </div>
    {/if}
</div>
