<script lang="ts">
    import type {HistorySource} from "$lib/api/history/history_repository";
    import {_} from "svelte-i18n";

    let {
        source = $bindable(),
    }: {
        source: HistorySource;
    } = $props();

    const sources: {value: HistorySource; label: string}[] = [
        {value: "optimized", label: "history.source.optimized"},
        {value: "raw", label: "history.source.raw"},
    ];
</script>

<!-- Two states, so a segmented control rather than a select: both options stay
     readable and one tap switches between them. -->
<div class="flex w-full flex-row gap-1 rounded-xl bg-card p-1" role="tablist">
    {#each sources as option (option.value)}
        <button
                type="button"
                role="tab"
                aria-selected={source === option.value}
                onclick={() => (source = option.value)}
                class="flex-1 cursor-pointer rounded-lg px-3 py-1.5 text-sm font-medium transition-colors"
                class:bg-muted={source === option.value}
                class:text-muted-foreground={source !== option.value}
        >
            {$_(option.label)}
        </button>
    {/each}
</div>
