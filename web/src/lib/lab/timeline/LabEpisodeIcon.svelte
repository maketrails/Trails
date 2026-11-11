<script lang="ts">
    import {CarIcon, CloudSlashIcon, MapPinIcon, PersonSimpleBikeIcon, PersonSimpleWalkIcon, QuestionIcon} from "phosphor-svelte";
    import type {LabEpisode} from "./lab_history";

    let {episode, size = 16}: {episode: LabEpisode; size?: number} = $props();
</script>

{#if episode.kind === "stay"}
    <MapPinIcon {size} />
{:else if episode.kind === "gap"}
    <CloudSlashIcon {size} />
{:else if episode.movement.type === "walking"}
    <PersonSimpleWalkIcon {size} />
{:else if episode.movement.type === "cycling"}
    <PersonSimpleBikeIcon {size} />
{:else if episode.movement.type === "travel"}
    <CarIcon {size} />
{:else}
    <QuestionIcon {size} />
{/if}
