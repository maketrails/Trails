<script lang="ts">
    import {_, locale} from "svelte-i18n";
    import {DeviceMobileIcon, FlaskIcon, MonitorIcon} from "phosphor-svelte";
    import type {TimelineRange} from "$lib/components/timeline";
    import DayStripProposal from "$lib/lab/timeline/DayStripProposal.svelte";
    import ScrubberProposal from "$lib/lab/timeline/ScrubberProposal.svelte";
    import StoryProposal from "$lib/lab/timeline/StoryProposal.svelte";
    import {createLabHistory} from "$lib/lab/timeline/lab_history";
    import type {TrailDisplayMode} from "$lib/state/trail_display_mode.svelte";

    /*
     * The timeline redesign lab: three prototypes of a different timeline, side by side
     * on generated data. Reachable only by its URL and linked from nowhere; deleting
     * this folder and `lib/lab` removes it entirely. See docs/design/timeline-redesign.md.
     */

    type Proposal = "day_strip" | "scrubber" | "story";
    const PROPOSALS: readonly Proposal[] = ["day_strip", "scrubber", "story"];

    const history = createLabHistory(new Date());

    let proposal = $state<Proposal>("day_strip");
    let mode = $state<TrailDisplayMode>("trail");
    let focus = $state<TimelineRange | null>(null);
    let narrow = $state(false);

    let focusFormat = $derived(new Intl.DateTimeFormat($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"}));

    /**
     * The layout puts every page in the card beside the map, far too narrow for a
     * timeline. The lab moves itself to the body and covers the whole window instead.
     */
    function portal(node: HTMLElement) {
        document.body.appendChild(node);
        return {destroy: () => node.remove()};
    }
</script>

<svelte:head>
    <meta name="robots" content="noindex" />
</svelte:head>

<div use:portal class="fixed inset-0 z-50 overflow-y-auto bg-background text-foreground">
    <div class="mx-auto flex max-w-4xl flex-col gap-4 px-4 py-6">
        <header class="flex flex-col gap-2">
            <span class="flex w-fit flex-row items-center gap-1.5 rounded-full bg-amber-500/15 px-2.5 py-1 text-xs font-semibold text-amber-700 dark:text-amber-300">
                <FlaskIcon size={14} />
                {$_("lab.timeline.badge")}
            </span>
            <h1 class="text-xl font-semibold">{$_("lab.timeline.title")}</h1>
            <p class="text-sm text-muted-foreground">{$_("lab.timeline.description")}</p>
        </header>

        <div class="flex flex-row flex-wrap items-center justify-between gap-2">
            <div role="tablist" aria-label={$_("lab.timeline.proposals.label")} class="flex flex-row flex-wrap gap-1 rounded-full border border-border bg-accent/65 p-1">
                {#each PROPOSALS as option, index (option)}
                    <button
                            type="button"
                            role="tab"
                            aria-selected={proposal === option}
                            onclick={() => {
                                proposal = option;
                                focus = null;
                            }}
                            class="h-8 cursor-pointer rounded-full px-3 text-xs font-medium transition-colors pointer-coarse:h-10
                                   {proposal === option ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground'}"
                    >
                        {String.fromCharCode(65 + index)} · {$_(`lab.timeline.proposals.${option}.name`)}
                    </button>
                {/each}
            </div>

            <div role="group" aria-label={$_("lab.timeline.preview_width.label")} class="flex flex-row gap-1 rounded-full border border-border bg-accent/65 p-1">
                {#each [false, true] as option (option)}
                    {@const label = $_(option ? "lab.timeline.preview_width.phone" : "lab.timeline.preview_width.wide")}
                    <button
                            type="button"
                            aria-pressed={narrow === option}
                            aria-label={label}
                            title={label}
                            onclick={() => (narrow = option)}
                            class="flex size-8 cursor-pointer items-center justify-center rounded-full transition-colors pointer-coarse:size-10
                                   {narrow === option ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'}"
                    >
                        {#if option}<DeviceMobileIcon size={16} />{:else}<MonitorIcon size={16} />{/if}
                    </button>
                {/each}
            </div>
        </div>

        <p class="text-sm">{$_(`lab.timeline.proposals.${proposal}.pitch`)}</p>

        <!-- The prototype, at full width or at a phone's. -->
        <div class="mx-auto w-full rounded-3xl border border-border bg-accent/65 p-3 shadow-2xl {narrow ? 'max-w-[360px]' : ''}">
            {#key proposal}
                {#if proposal === "day_strip"}
                    <DayStripProposal {history} bind:mode bind:focus />
                {:else if proposal === "scrubber"}
                    <ScrubberProposal {history} bind:mode bind:focus />
                {:else}
                    <StoryProposal {history} bind:mode bind:focus />
                {/if}
            {/key}
        </div>

        <!-- What the map would do with it: the prototypes do not touch the real map. -->
        <p class="rounded-xl border border-dashed border-border px-3 py-2 text-xs text-muted-foreground">
            <span class="font-medium text-foreground">{$_("lab.timeline.map_stand_in.label")}</span>
            {focus == null
                ? $_("lab.timeline.map_stand_in.nothing", {values: {mode: $_(`map.trail_display.modes.${mode}`)}})
                : $_("lab.timeline.map_stand_in.focus", {
                    values: {range: focusFormat.formatRange(focus.start, focus.end), mode: $_(`map.trail_display.modes.${mode}`)},
                })}
        </p>
    </div>
</div>
