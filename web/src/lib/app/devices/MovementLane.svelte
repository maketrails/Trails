<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import type {MovementItem} from "$lib/api/history/history_repository";
    import type {TimelineLaneScale} from "$lib/components/timeline";
    import { CarIcon, PersonSimpleBikeIcon, PersonSimpleWalkIcon } from "phosphor-svelte";

    let {
        movements,
        scale,
    }: {
        /** Oldest first, as the history hands them out. */
        movements: MovementItem[];
        /** Where the timeline currently puts its moments. */
        scale: TimelineLaneScale;
    } = $props();

    /**
     * Below this a block has no room for its padding and icon and is only its colour.
     * Padding on a narrower one would make it wider than the movement it stands for — the
     * box cannot shrink below its padding — and run it into its neighbours.
     */
    const ICON_MIN_WIDTH = 32;

    /** Below this a block has no room for its label and only shows its icon. */
    const LABEL_MIN_WIDTH = 72;

    /** The types this view knows; anything else the server sends is shown as unknown. */
    const KNOWN_TYPES = ["walking", "cycling", "travel"];

    const COLORS: Record<string, string> = {
        walking: "bg-sky-600",
        cycling: "bg-orange-500",
        travel: "bg-emerald-600",
    };

    function typeOf(movement: MovementItem): string {
        return KNOWN_TYPES.includes(movement.type) ? movement.type : "unknown";
    }

    /** Kilometres from one kilometre on, metres below — whatever reads at a glance. */
    let distanceFormat = $derived({
        meters: new Intl.NumberFormat($locale ?? undefined, {style: "unit", unit: "meter", maximumFractionDigits: 0}),
        kilometers: new Intl.NumberFormat($locale ?? undefined, {style: "unit", unit: "kilometer", maximumFractionDigits: 1}),
    });

    function distance(meters: number): string {
        return meters < 1_000 ? distanceFormat.meters.format(meters) : distanceFormat.kilometers.format(meters / 1_000);
    }

    let timeFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"}));

    /**
     * The movements that reach into the window, placed in pixels. Clipped to the track,
     * so a movement running off either edge still shows its label in what is visible.
     */
    let blocks = $derived.by(() => {
        const {start, end, width} = scale;
        if (width <= 0 || end <= start) return [];

        const perMs = width / (end - start);
        return movements
            .filter((movement) => movement.to > start && movement.from < end)
            .map((movement) => {
                const left = Math.max(0, (movement.from - start) * perMs);
                const right = Math.min(width, (movement.to - start) * perMs);
                // A hairline at least, so a movement too short for its pixel still shows.
                return {movement, type: typeOf(movement), left, width: Math.max(1, right - left)};
            });
    });
</script>

<!-- One block per movement, coloured by its type. Hovering a block names it in full, the
     label inside only fits where the block is wide enough. -->
<ul aria-label={$_("history.movements.label")} class="relative h-full w-full">
    {#each blocks as block (block.movement.id)}
        {@const label = $_(`history.movements.type.${block.type}`)}
        <li
                class="absolute inset-y-0 flex flex-row gap-1 items-center overflow-hidden rounded-lg text-[11px] font-medium text-white
                       {block.width >= ICON_MIN_WIDTH ? 'px-2' : ''}
                       {COLORS[block.type] ?? 'bg-muted-foreground/60'}"
                style:left="{block.left}px"
                style:width="{block.width}px"
                title="{label} · {distance(block.movement.distance_meters)}
{timeFormat.formatRange(block.movement.from, block.movement.to)}"
        >
            {#if block.width >= ICON_MIN_WIDTH}
                {#if block.type === "walking"}
                    <PersonSimpleWalkIcon class="size-4 shrink-0" />
                {:else if block.type === "cycling"}
                    <PersonSimpleBikeIcon class="size-4 shrink-0" />
                {:else if block.type === "travel"}
                    <CarIcon class="size-4 shrink-0" />
                {/if}
            {/if}

            {#if block.width >= LABEL_MIN_WIDTH}
                <span class="truncate">{label} · {distance(block.movement.distance_meters)}</span>
            {/if}
        </li>
    {/each}
</ul>
