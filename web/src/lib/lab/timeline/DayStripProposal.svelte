<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import {CaretDownIcon} from "phosphor-svelte";
    import MovementLane from "$lib/app/devices/MovementLane.svelte";
    import {formatDistance} from "$lib/app/movements";
    import {pickedRange, type TimelineRange} from "$lib/components/timeline";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";
    import LabModeSwitch from "./LabModeSwitch.svelte";
    import LabOverview from "./LabOverview.svelte";
    import {daysOf, episodesBetween, formatDuration, totals, type LabDay, type LabHistory} from "./lab_history";

    /**
     * Proposal A — the day strip. One row per day, like a calendar, each row the same
     * 24 hours wide, so days can be compared at a glance: when did I leave, how long was
     * I out. A tap opens a day into a detail lane; a tap on a movement there focuses it.
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

    /** Newest first: today is what is looked for first. */
    let days = $derived(daysOf(history.oldest, history.newest).reverse());

    let openDay = $state<number | null>(null);
    let detailWidth = $state(0);

    let dayFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {weekday: "short", day: "numeric", month: "short"}));
    let hourFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {hour: "numeric"}));
    let timeFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {hour: "2-digit", minute: "2-digit"}));

    const percent = (day: LabDay, time: number) => ((time - day.start) / (day.end - day.start)) * 100;

    function summary(day: LabDay) {
        const movements = history.movements.filter((movement) => movement.from >= day.start && movement.from < day.end);
        return {count: movements.length, ...totals(movements)};
    }

    function toggle(day: LabDay) {
        openDay = openDay === day.start ? null : day.start;
        focus = openDay == null ? null : {start: new Date(Math.max(day.start, history.oldest)), end: new Date(Math.min(day.end, history.newest))};
    }

    /** A tap on a movement in the open day focuses it, the same marker the real timeline uses. */
    function pick(event: MouseEvent) {
        const picked = pickedRange(event.target);
        if (picked != null) focus = picked;
    }
</script>

<div class="flex flex-col gap-3">
    <div class="flex flex-row flex-wrap items-center justify-between gap-2">
        <LabModeSwitch bind:mode />
        <span class="text-xs text-muted-foreground">{$_("lab.timeline.day_strip.hint")}</span>
    </div>

    <!-- The hours every row shares, so the eye can run down a column. -->
    <div aria-hidden="true" class="flex flex-row gap-2 pl-[4.5rem] pr-[5.75rem] text-[10px] text-muted-foreground tabular-nums">
        <div class="relative h-3 flex-1">
            {#each [0, 6, 12, 18] as hour (hour)}
                <span class="absolute" style:left="{(hour / 24) * 100}%">
                    {hourFormat.format(new Date(2000, 0, 1, hour))}
                </span>
            {/each}
        </div>
    </div>

    <ul aria-label={$_("lab.timeline.day_strip.days")} class="flex flex-col gap-1">
        {#each days as day (day.start)}
            {@const total = summary(day)}
            {@const open = openDay === day.start}
            <li class="flex flex-col gap-2 rounded-xl {open ? 'bg-card/70 p-2' : ''}">
                <button
                        type="button"
                        aria-expanded={open}
                        onclick={() => toggle(day)}
                        class="flex w-full cursor-pointer flex-row items-center gap-2 rounded-lg px-0 py-1 text-left transition-colors hover:bg-accent pointer-coarse:py-2"
                >
                    <span class="w-16 shrink-0 truncate text-xs font-medium">{dayFormat.format(day.start)}</span>
                    <span class="relative h-5 flex-1">
                        <LabOverview {history} start={day.start} end={day.end} separators={false} />
                        <!-- The part of today that has not happened yet. -->
                        {#if day.end > history.newest}
                            <span
                                    class="absolute inset-y-0 right-0 rounded-r-md border border-dashed border-muted-foreground/30 bg-background/60"
                                    style:left="{percent(day, history.newest)}%"
                            ></span>
                        {/if}
                    </span>
                    <span class="w-16 shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                        {formatDistance(total.distance, $locale)}
                    </span>
                    <CaretDownIcon size={12} class="shrink-0 text-muted-foreground transition-transform {open ? 'rotate-180' : ''}" />
                </button>

                {#if open}
                    {@const stays = episodesBetween(history.episodes, day.start, day.end).filter((episode) => episode.kind === "stay")}
                    <div class="flex flex-col gap-1">
                        <p class="text-xs text-muted-foreground">
                            {$_("lab.timeline.day_strip.summary", {
                                values: {
                                    count: total.count,
                                    distance: formatDistance(total.distance, $locale),
                                    moving: formatDuration(total.moving, $locale),
                                },
                            })}
                        </p>

                        <!-- The open day at full width: where the person was, and how they
                             moved in between, with the real movement lane. -->
                        <!-- svelte-ignore a11y_click_events_have_key_events -->
                        <!-- svelte-ignore a11y_no_static_element_interactions -->
                        <div bind:clientWidth={detailWidth} onclick={pick} class="relative flex flex-col gap-1">
                            <div class="relative h-5 text-[10px] text-muted-foreground">
                                {#each stays as stay (stay.id)}
                                    {@const left = Math.max(0, percent(day, stay.from))}
                                    {@const width = Math.min(100, percent(day, stay.to)) - left}
                                    {#if (width / 100) * detailWidth >= 44}
                                        <span
                                                class="absolute truncate rounded bg-muted-foreground/10 px-1 leading-5"
                                                style:left="{left}%"
                                                style:width="{width}%"
                                                title="{$_(`lab.timeline.places.${stay.place}`)} · {timeFormat.formatRange(stay.from, stay.to)}"
                                        >
                                            {$_(`lab.timeline.places.${stay.place}`)}
                                        </span>
                                    {/if}
                                {/each}
                            </div>
                            <div class="relative h-8">
                                <MovementLane movements={history.movements} scale={{start: day.start, end: day.end, width: detailWidth}} />
                            </div>
                            <div aria-hidden="true" class="relative h-3 text-[10px] text-muted-foreground tabular-nums">
                                {#each [0, 3, 6, 9, 12, 15, 18, 21] as hour (hour)}
                                    <span class="absolute border-l border-muted-foreground/30 pl-0.5" style:left="{(hour / 24) * 100}%">
                                        {hourFormat.format(new Date(day.start).setHours(hour))}
                                    </span>
                                {/each}
                            </div>
                        </div>
                    </div>
                {/if}
            </li>
        {/each}
    </ul>
</div>
