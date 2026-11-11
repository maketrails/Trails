<script lang="ts">
    import {MOVEMENT_COLORS, movementTypeOf} from "$lib/app/movements";
    import {daysOf, episodesBetween, type LabHistory} from "./lab_history";

    let {
        history,
        start = history.oldest,
        end = history.newest,
        separators = true,
    }: {
        history: LabHistory;
        /** The stretch drawn across the full width; the whole history by default. */
        start?: number;
        end?: number;
        /** Whether midnights are drawn as thin lines. */
        separators?: boolean;
    } = $props();

    const percent = (time: number) => ((time - start) / Math.max(end - start, 1)) * 100;

    let episodes = $derived(episodesBetween(history.episodes, start, end).filter((episode) => episode.kind !== "stay"));
    let midnights = $derived(daysOf(start, end).map((day) => day.start).filter((time) => time > start && time < end));
</script>

<!-- Everything that happened in a stretch, drawn in percent so it fits any width: movements
     in their colours, gaps hatched, stays as the empty track between them. Decorative — the
     component it sits in names it. -->
<div aria-hidden="true" class="relative h-full w-full overflow-hidden rounded-md bg-muted-foreground/10">
    {#if separators}
        {#each midnights as midnight (midnight)}
            <div class="absolute inset-y-0 w-px bg-muted-foreground/30" style:left="{percent(midnight)}%"></div>
        {/each}
    {/if}

    {#each episodes as episode (episode.id)}
        {@const left = Math.max(0, percent(episode.from))}
        {@const width = Math.max(0.15, Math.min(100, percent(episode.to)) - left)}
        {#if episode.kind === "gap"}
            <div class="lab-gap absolute inset-y-0" style:left="{left}%" style:width="{width}%"></div>
        {:else if episode.kind === "movement"}
            {@const type = movementTypeOf(episode.movement)}
            <div
                    class="absolute inset-y-0 {type == null ? 'bg-muted-foreground/60' : ''}"
                    style:background-color={type == null ? undefined : MOVEMENT_COLORS[type]}
                    style:left="{left}%"
                    style:width="{width}%"
            ></div>
        {/if}
    {/each}
</div>

<style>
    /* A stretch without data, hatched so it never reads as a stay. */
    :global(.lab-gap) {
        background-image: repeating-linear-gradient(
                135deg,
                color-mix(in oklab, var(--muted-foreground) 55%, transparent) 0 1.5px,
                transparent 1.5px 5px
        );
    }
</style>
