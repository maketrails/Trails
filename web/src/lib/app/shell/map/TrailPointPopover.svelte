<script lang="ts">
    import type {HistoryPoint} from "$lib/api/history/history_repository";
    import dayjs from "$lib/dayjs";
    import {_} from "svelte-i18n";

    /**
     * What the hovered position on the trail was: when it was recorded, how precise it is,
     * and what the battery was at. Held in a container object rather than passed directly
     * because the map mounts this component imperatively — mutating the container is what
     * lets the popover follow the cursor.
     */
    let {state}: {state: {point: HistoryPoint | null}} = $props();

    let point = $derived(state.point);

    // `L LT` is the locale's own date and short time, never a hardcoded pattern.
    let recorded = $derived(point == null ? null : dayjs(point.timestamp));

    /**
     * A bearing without an accuracy is a position the device could not tell a direction
     * for, so it is left out rather than shown as a confident 0°.
     */
    let bearing = $derived(
        point == null || point.bearing_accuracy == null
            ? null
            : $_("history.point.bearing", {
                values: {
                    degrees: Math.round(point.bearing),
                    accuracy: Math.round(point.bearing_accuracy),
                },
            }),
    );
</script>

<!-- Never a mouse target: the popover sits where the cursor is heading, and swallowing
     the move events would freeze the very hover that put it there. -->
{#if point != null && recorded != null}
    <div
            class="pointer-events-none select-none rounded-lg border border-border bg-card/95 px-2.5 py-1.5
                   text-card-foreground shadow-lg backdrop-blur-sm whitespace-nowrap"
    >
        <p class="text-xs font-medium">{recorded.format("L LT")}</p>
        <p class="text-[11px] text-muted-foreground">{recorded.fromNow()}</p>

        <p class="mt-1 text-[11px] tabular-nums text-muted-foreground">
            {$_("history.point.location_accuracy", {values: {meters: Math.round(point.location_accuracy)}})}
            {#if bearing != null}
                <span aria-hidden="true"> · </span>{bearing}
            {/if}
        </p>

        {#if point.battery != null}
            <p class="text-[11px] tabular-nums text-muted-foreground">
                {$_(point.battery.is_charging ? "battery.level.charging" : "battery.level.not_charging", {
                    values: {percentage: point.battery.percentage},
                })}
            </p>
        {/if}
    </div>
{/if}
