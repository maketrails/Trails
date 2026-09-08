<script lang="ts">
    import {CalendarDate, type DateValue} from "@internationalized/date";
    import {CalendarBlankIcon} from "phosphor-svelte";
    import {_, locale} from "svelte-i18n";
    import {Input} from "$lib/components/ui/input";
    import {Popover, PopoverContent, PopoverTrigger} from "$lib/components/ui/popover";
    import {RangeCalendar} from "$lib/components/ui/range-calendar";
    import type {TimelineRange} from "./timeline_window";

    let {
        range,
        onchange,
    }: {
        /** The marked stretch this edits. */
        range: TimelineRange;
        /** Where an edited range goes. */
        onchange: (range: TimelineRange) => void;
    } = $props();

    const pad = (value: number) => String(value).padStart(2, "0");

    /** A day the calendar can hold, taken off the local clock. */
    const toCalendarDate = (date: Date) => new CalendarDate(date.getFullYear(), date.getMonth() + 1, date.getDate());

    /** The same moment on another day, keeping its time. */
    const onDay = (date: Date, day: DateValue) =>
        new Date(day.year, day.month - 1, day.day, date.getHours(), date.getMinutes(), date.getSeconds());

    /** The same day at another time. Seconds are dropped: the field only offers minutes. */
    function atTime(date: Date, time: string): Date {
        const [hours, minutes] = time.split(":").map(Number);
        if (Number.isNaN(hours) || Number.isNaN(minutes)) return date;

        const next = new Date(date);
        next.setHours(hours, minutes, 0, 0);
        return next;
    }

    /** Keeps the two ends in order, however they were edited. */
    const ordered = (start: Date, end: Date): TimelineRange =>
        start.getTime() <= end.getTime() ? {start, end} : {start: end, end: start};

    let days = $derived({start: toCalendarDate(range.start), end: toCalendarDate(range.end)});
    let times = $derived({
        start: `${pad(range.start.getHours())}:${pad(range.start.getMinutes())}`,
        end: `${pad(range.end.getHours())}:${pad(range.end.getMinutes())}`,
    });

    let label = $derived(
        new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"})
            .formatRange(range.start, range.end),
    );

    function onDaysPicked(next: {start?: DateValue; end?: DateValue} | undefined) {
        if (next?.start == null) return;

        // Picking the first day of a new range leaves the second one open. Until it
        // arrives, both ends sit on that day, which is also what the calendar draws.
        const picked = [next.start, next.end ?? next.start].sort((left, right) => left.compare(right));

        // Which of the two the calendar calls `start` is its own business — it moves
        // that anchor around as a range is redrawn — so the earlier time goes on the
        // earlier day rather than on whichever day it happened to name first.
        onchange(ordered(onDay(range.start, picked[0]), onDay(range.end, picked[1])));
    }
</script>

<Popover>
    <PopoverTrigger
            class="flex min-w-0 cursor-pointer flex-row items-center gap-1.5 rounded-md px-1.5 py-0.5 text-xs text-card-foreground transition-colors hover:bg-accent"
            title={$_("timeline.range.edit")}
    >
        <CalendarBlankIcon size={14} />
        <span class="truncate">{label}</span>
    </PopoverTrigger>

    <PopoverContent class="w-auto p-0" align="start">
        <!-- A function binding rather than a plain value, so the calendar holds no copy
             of its own: the marked range stays the one truth, and dragging an end while
             this is open moves the calendar with it. -->
        <RangeCalendar
                bind:value={() => days, onDaysPicked}
                locale={$locale ?? "en"}
                captionLayout="dropdown"
        />

        <!-- The calendar only goes down to a day; these two carry the time of day,
             which on a timeline of a single afternoon is the whole point. -->
        <div class="flex flex-row items-end gap-2 border-t border-border p-3">
            <label class="flex flex-1 flex-col gap-1 text-xs text-muted-foreground">
                {$_("timeline.range.from")}
                <Input
                        type="time"
                        value={times.start}
                        onchange={(event) => onchange(ordered(atTime(range.start, event.currentTarget.value), range.end))}
                />
            </label>
            <label class="flex flex-1 flex-col gap-1 text-xs text-muted-foreground">
                {$_("timeline.range.to")}
                <Input
                        type="time"
                        value={times.end}
                        onchange={(event) => onchange(ordered(range.start, atTime(range.end, event.currentTarget.value)))}
                />
            </label>
        </div>
    </PopoverContent>
</Popover>
