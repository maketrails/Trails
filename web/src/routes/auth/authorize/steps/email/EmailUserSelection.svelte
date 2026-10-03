<script lang="ts">
    import {EmailUserSelectionRenderer} from "@julius-babies/authentikt-svelte";
    import Content from "./Content.svelte";
</script>

<EmailUserSelectionRenderer>
    {#snippet children(plugin)}
        <Content plugin={plugin}/>
    {/snippet}
</EmailUserSelectionRenderer>