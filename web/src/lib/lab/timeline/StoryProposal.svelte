<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import RangeShortcuts from "$lib/app/shell/map/RangeShortcuts.svelte";
    import {formatDistance, MOVEMENT_COLORS, movementTypeOf} from "$lib/app/movements";
    import type {TimelineRange} from "$lib/components/timeline";
    import {shortcutRange} from "$lib/components/timeline/range_shortcuts";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";
    import LabEpisodeIcon from "./LabEpisodeIcon.svelte";
    import LabModeSwitch from "./LabModeSwitch.svelte";
    import LabOverview from "./LabOverview.svelte";
    import {daysOf, episodesBetween, formatDuration, totals, type LabEpisode, type LabHistory} from "./lab_history";

    /**
     * Proposal C — a story. The history read as what it is to a person: stays and the
     * trips between them, newest first, grouped by day, with places, durations and
     * distances. A slim scrubber above shows where in the range the list is, and a
     * press on it jumps the list there. The shortcuts filter the list.
     */
    let {
        history,
        mode = $bindable(),
        focus = $bindable(),
    }: {
        history: LabHistory;
        mode: TrailDisplayMode;
        /** What the map would frame. */
        focus: TimelineRange | null;
    } = $props();

    // The range the list covers. Opens on the last week, like the real timeline.
    let range = $state<TimelineRange>(lastWeek());

    function lastWeek(): TimelineRange {
        return shortcutRange("last_week", new Date(), new Date(history.oldest), new Date(history.newest));
    }

    let start = $derived(range.start.getTime());
    let end = $derived(range.end.getTime());

    /** One list entry: an episode, cut to the day it is listed under. */
    interface Entry {
        key: string;
        episode: LabEpisode;
        from: number;
        to: number;
    }

    let groups = $derived(
        daysOf(start, end)
            .reverse()
            .map((day) => {
                const from = Math.max(day.start, start);
                const to = Math.min(day.end, end);
                const entries: Entry[] = episodesBetween(history.episodes, from, to)
                    .map((episode) => ({key: `${episode.id}-${day.start}`, episode, from: Math.max(episode.from, from), to: Math.min(episode.to, to)}))
                    .reverse();
                const movements = entries.flatMap((entry) => (entry.episode.kind === "movement" ? [entry.episode.movement] : []));
                return {day, entries, distance: totals(movements).distance};
            })
            .filter((group) => group.entries.length > 0),
    );

    let selected = $state<string | null>(null);

    function select(entry: Entry) {
        selected = selected === entry.key ? null : entry.key;
        focus = selected == null ? range : {start: new Date(entry.from), end: new Date(entry.to)};
    }

    $effect(() => {
        if (selected == null) focus = range;
    });

    let list = $state<HTMLElement | null>(null);

    /** The stretch of time the list shows right now, for the scrubber's highlight. */
    let visible = $state<{from: number; to: number} | null>(null);

    function measure() {
        if (list == null) return;
        const box = list.getBoundingClientRect();
        let from = Infinity;
        let to = -Infinity;
        for (const element of list.querySelectorAll<HTMLElement>("[data-from]")) {
            const rect = element.getBoundingClientRect();
            if (rect.bottom < box.top || rect.top > box.bottom) continue;
            from = Math.min(from, Number(element.dataset.from));
            to = Math.max(to, Number(element.dataset.to));
        }
        visible = Number.isFinite(from) ? {from, to} : null;
    }

    let frame = 0;
    function onScroll() {
        cancelAnimationFrame(frame);
        frame = requestAnimationFrame(measure);
    }

    $effect(() => {
        void groups;
        requestAnimationFrame(measure);
    });

    /** Scrolls the list so the entry around [time] sits in its middle. */
    function scrollTo(time: number) {
        if (list == null) return;
        const target = [...list.querySelectorAll<HTMLElement>("[data-from]")]
            .find((element) => time >= Number(element.dataset.from) && time <= Number(element.dataset.to));
        if (target == null) return;
        const offset = target.getBoundingClientRect().top - list.getBoundingClientRect().top;
        list.scrollTop += offset - list.clientHeight / 2 + target.clientHeight / 2;
    }

    let scrubbing = false;

    function onScrub(event: PointerEvent) {
        if (event.type === "pointerdown") {
            scrubbing = true;
            (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
        }
        if (!scrubbing) return;
        const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
        const anchor = Math.min(Math.max((event.clientX - rect.left) / rect.width, 0), 1);
        scrollTo(start + anchor * (end - start));
    }

    const percent = (time: number) => ((time - start) / Math.max(end - start, 1)) * 100;

    let dayFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {weekday: "long", day: "numeric", month: "long"}));
    let timeFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {hour: "2-digit", minute: "2-digit"}));
    const place = (key: string) => $_(`lab.timeline.places.${key}`);
</script>

<div class="flex flex-col gap-3">
    <div class="flex flex-row flex-wrap items-center gap-2">
        <LabModeSwitch bind:mode />
        <RangeShortcuts
                grantSeconds={null}
                oldest={new Date(history.oldest)}
                newest={new Date(history.newest)}
                view={range}
                onselect={(next) => {
                    range = next;
                    selected = null;
                    list?.scrollTo({top: 0});
                }}
        />
    </div>

    <!-- The slim scrubber: the range at a glance, the part the list shows framed. -->
    <div
            role="slider"
            tabindex="0"
            aria-label={$_("lab.timeline.story.scrubber")}
            aria-valuemin={start}
            aria-valuemax={end}
            aria-valuenow={visible?.to ?? end}
            onpointerdown={onScrub}
            onpointermove={onScrub}
            onpointerup={() => (scrubbing = false)}
            onpointercancel={() => (scrubbing = false)}
            class="relative h-4 cursor-pointer touch-none select-none pointer-coarse:h-8"
    >
        <LabOverview {history} {start} {end} />
        {#if visible != null}
            <div
                    class="pointer-events-none absolute -inset-y-1 rounded-md border-2 border-primary bg-primary/10"
                    style:left="{Math.max(0, percent(visible.from))}%"
                    style:width="max(6px, {Math.min(100, percent(visible.to)) - Math.max(0, percent(visible.from))}%)"
            ></div>
        {/if}
    </div>

    <div bind:this={list} onscroll={onScroll} class="scroll-thin relative h-[28rem] overflow-y-auto rounded-2xl bg-card/40">
        {#if groups.length === 0}
            <p class="p-4 text-sm text-muted-foreground">{$_("lab.timeline.story.empty")}</p>
        {/if}

        {#each groups as group (group.day.start)}
            <section aria-label={dayFormat.format(group.day.start)}>
                <h3 class="sticky top-0 z-10 flex flex-row items-baseline justify-between gap-2 border-b border-border bg-background/90 px-4 py-2 text-sm font-semibold backdrop-blur">
                    <span class="truncate">{dayFormat.format(group.day.start)}</span>
                    <span class="shrink-0 text-xs font-normal tabular-nums text-muted-foreground">{formatDistance(group.distance, $locale)}</span>
                </h3>

                <ol class="relative flex flex-col py-1">
                    {#each group.entries as entry (entry.key)}
                        {@const episode = entry.episode}
                        {@const type = episode.kind === "movement" ? movementTypeOf(episode.movement) : null}
                        {@const color = type == null ? undefined : MOVEMENT_COLORS[type]}
                        <li data-from={entry.from} data-to={entry.to}>
                            <button
                                    type="button"
                                    aria-pressed={selected === entry.key}
                                    onclick={() => select(entry)}
                                    class="group flex w-full cursor-pointer flex-row items-stretch gap-3 px-4 text-left transition-colors hover:bg-accent
                                           {selected === entry.key ? 'bg-primary/10' : ''}"
                            >
                                <!-- The rail: a stay is a stop on it, a trip a stretch of it in its colour. -->
                                <span class="relative flex w-7 shrink-0 flex-col items-center">
                                    <span
                                            class="absolute inset-y-0 w-1 {episode.kind === 'gap' ? 'lab-gap w-1.5' : episode.kind === 'stay' ? 'bg-muted-foreground/25' : ''}"
                                            style:background-color={color}
                                    ></span>
                                    <span
                                            class="relative my-2 flex size-7 items-center justify-center rounded-full border-2 border-background
                                                   {episode.kind === 'movement' ? 'text-white' : 'bg-muted text-muted-foreground'}"
                                            style:background-color={color}
                                    >
                                        <LabEpisodeIcon {episode} size={14} />
                                    </span>
                                </span>

                                <span class="flex min-w-0 flex-1 flex-col justify-center py-2">
                                    {#if episode.kind === "stay"}
                                        <span class="truncate text-sm font-medium">{place(episode.place)}</span>
                                        <span class="truncate text-xs tabular-nums text-muted-foreground">
                                            {timeFormat.formatRange(entry.from, entry.to)} · {formatDuration(entry.to - entry.from, $locale)}
                                        </span>
                                    {:else if episode.kind === "movement"}
                                        <span class="truncate text-sm font-medium">
                                            {$_(`history.movements.type.${type ?? "unknown"}`)} · {formatDistance(episode.movement.distance_meters, $locale)}
                                        </span>
                                        <span class="truncate text-xs tabular-nums text-muted-foreground">
                                            {$_("lab.timeline.episodes.route", {values: {from: place(episode.origin), to: place(episode.destination)}})}
                                            · {formatDuration(entry.to - entry.from, $locale)}
                                        </span>
                                    {:else}
                                        <span class="truncate text-sm italic text-muted-foreground">
                                            {$_("lab.timeline.episodes.no_data_for", {values: {duration: formatDuration(entry.to - entry.from, $locale)}})}
                                        </span>
                                        <span class="truncate text-xs tabular-nums text-muted-foreground">{timeFormat.formatRange(entry.from, entry.to)}</span>
                                    {/if}
                                </span>
                            </button>
                        </li>
                    {/each}
                </ol>
            </section>
        {/each}
    </div>
</div>
