<script lang="ts">
    import {_} from "svelte-i18n";
    import {
        availableShortcuts,
        isShortcutActive,
        shortcutRange,
    } from "$lib/components/timeline/range_shortcuts";
    import type {TimelineRange, TimelineView} from "$lib/components/timeline";

    let {
        grantSeconds,
        oldest,
        newest,
        view,
        onselect,
    }: {
        /**
         * How far back the viewer may read, in seconds; `null` (or negative) when
         * nothing is cut off. Shortcuts reaching further than that are left out.
         */
        grantSeconds: number | null;
        /** The first and last moment there is data for. */
        oldest: Date;
        newest: Date;
        /** The window the timeline shows now; the matching shortcut is highlighted. */
        view: TimelineView | null;
        /** Called with the window a pressed shortcut stands for. */
        onselect: (range: TimelineRange) => void;
    } = $props();

    // Read when rendered: the window a shortcut stands for moves with the clock, but
    // nothing on the page needs to tick for that.
    let shortcuts = $derived(availableShortcuts(grantSeconds, new Date()));
</script>

<!-- Same pill as the mode switch beside it, with words instead of icons: a range is
     named by its length, which no icon says. -->
<div
        role="group"
        aria-label={$_("timeline.shortcuts.label")}
        class="pointer-events-auto flex shrink-0 flex-row items-center gap-0.5 rounded-full border border-border bg-accent/65 p-1 text-card-foreground shadow-2xl backdrop-blur-lg"
>
    {#each shortcuts as shortcut (shortcut)}
        {@const active = isShortcutActive(shortcut, view, new Date(), oldest, newest)}
        <button
                type="button"
                aria-pressed={active}
                title={$_(`timeline.shortcuts.${shortcut}.title`)}
                onclick={() => onselect(shortcutRange(shortcut, new Date(), oldest, newest))}
                class="flex h-7 cursor-pointer flex-row items-center whitespace-nowrap rounded-full px-2 text-xs font-medium transition-colors pointer-coarse:h-10 pointer-coarse:px-3
                   {active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-card-foreground'}"
        >
            {$_(`timeline.shortcuts.${shortcut}.label`)}
        </button>
    {/each}
</div>
