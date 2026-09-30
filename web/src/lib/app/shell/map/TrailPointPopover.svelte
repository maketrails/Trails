<script lang="ts">
    import type {HistoryPoint, MovementItem} from "$lib/api/history/history_repository";
    import {formatDistance, MOVEMENT_COLORS, movementTypeOf} from "$lib/app/movements";
    import dayjs from "$lib/dayjs";
    import {_, locale} from "svelte-i18n";

    /**
     * What the hovered position on the trail was: when it was recorded, how precise it is,
     * and what the battery was at. Held in a container object rather than passed directly
     * because the map mounts this component imperatively — mutating the container is what
     * lets the popover follow the cursor.
     */
    let {state}: {state: {point: HistoryPoint | null; movement: MovementItem | null}} = $props();

    let point = $derived(state.point);

    /** The movement the puck stands on, if any — shown above the position it passes. */
    let movement = $derived(state.movement);
    let movementType = $derived(movement == null ? null : movementTypeOf(movement));

    let movementTimes = $derived(
        movement == null
            ? null
            : new Intl.DateTimeFormat($locale ?? undefined, {timeStyle: "short"}).formatRange(movement.from, movement.to),
    );

    // Without suffix: "11 minutes", not "in 11 minutes".
    let movementDuration = $derived(movement == null ? null : dayjs(movement.to).from(dayjs(movement.from), true));

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
        {#if movement != null}
            <div class="mb-1.5 border-b border-border pb-1.5">
                <p class="flex flex-row items-center gap-1.5 text-xs font-medium">
                    <span
                            class="size-2 shrink-0 rounded-full {movementType == null ? 'bg-muted-foreground' : ''}"
                            style:background-color={movementType == null ? undefined : MOVEMENT_COLORS[movementType]}
                            aria-hidden="true"
                    ></span>
                    {$_(`history.movements.type.${movementType ?? "unknown"}`)}
                    <span aria-hidden="true">·</span>
                    <span class="tabular-nums">{formatDistance(movement.distance_meters, $locale)}</span>
                </p>
                <p class="text-[11px] tabular-nums text-muted-foreground">{movementTimes} · {movementDuration}</p>
            </div>
        {/if}

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
