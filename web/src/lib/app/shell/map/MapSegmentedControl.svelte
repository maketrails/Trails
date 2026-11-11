<script lang="ts" module>
    import type {Component} from "svelte";

    /** One choice: what it stands for, the message key it is labelled with, and its icon. */
    export interface SegmentedOption<V extends string> {
        value: V;
        labelKey: string;
        icon: Component<{size?: number; weight?: "regular" | "fill"}>;
    }
</script>

<script lang="ts" generics="T extends string">
    import {_} from "svelte-i18n";

    let {
        options,
        value,
        onselect,
        labelKey,
    }: {
        options: SegmentedOption<T>[];
        value: T;
        onselect: (value: T) => void;
        /** Message key naming the group as a whole, for assistive technology. */
        labelKey: string;
    } = $props();
</script>

<!-- A pill in the map's own visual language — the camera switch's translucent
     surface, laid out in a row. Few enough choices that all of them stay readable
     and one tap switches. On narrow strips only the icons are left, and the label
     moves into the tooltip and the accessible name. -->
<div
        role="group"
        aria-label={$_(labelKey)}
        class="pointer-events-auto flex shrink-0 flex-row items-center gap-0.5 rounded-full border border-border bg-accent/65 p-1 text-card-foreground shadow-2xl backdrop-blur-lg"
>
    {#each options as option (option.value)}
        {@const active = option.value === value}
        {@const label = $_(option.labelKey)}
        <button
                type="button"
                aria-pressed={active}
                aria-label={label}
                title={label}
                onclick={() => onselect(option.value)}
                class="flex h-7 cursor-pointer flex-row items-center gap-1.5 rounded-full px-2 text-xs font-medium transition-colors pointer-coarse:h-10 pointer-coarse:px-3
                   {active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent hover:text-card-foreground'}"
        >
            <option.icon size={16} weight={active ? "fill" : "regular"} />
            <span class="hidden whitespace-nowrap lg:inline">{label}</span>
        </button>
    {/each}
</div>
