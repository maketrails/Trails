<script lang="ts">
    import {PasswordRenderer} from "@julius-babies/authentikt-svelte";
    import Content from "./Content.svelte";
</script>

<PasswordRenderer>
    {#snippet children(plugin)}
        <Content plugin={plugin}/>
    {/snippet}
</PasswordRenderer>