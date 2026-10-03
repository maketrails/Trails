<script lang="ts">
    import {Label} from "$lib/components/ui/label";
    import {Input} from "$lib/components/ui/input";
    import {slide} from "svelte/transition";
    import {Button} from "$lib/components/ui/button";
    import {ArrowRight, Loader} from "@lucide/svelte";
    import {type EmailUserSelectionPluginInstance} from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";

    const id = $props.id();

    let {
        plugin,
    }: {
        plugin: EmailUserSelectionPluginInstance
    } = $props();

    let inputField: HTMLInputElement | null = $state(null);

    $effect(() => {
        if (inputField) inputField.focus();
    })

    $effect(() => {
        if ((plugin.status === "user_not_existing" || plugin.status === "error") && inputField) inputField.focus();
    })
</script>

<div class="w-full h-full flex flex-col">
    <form class="w-full flex flex-col gap-1.5" onsubmit={plugin.submit}>
        <Label for="email-{id}">{$_("auth.email.label")}</Label>
        <Input
                type="user"
                autocorrect="off"
                autocapitalize="none"
                id="email-{id}"
                inputmode="email"
                bind:value={plugin.email}
                bind:ref={inputField}
                placeholder={$_("auth.email.placeholder")}
                onsubmit={plugin.submit}
        />
    </form>

    {#if plugin.status !== "ready" && plugin.status !== "loading"}
                                        <span
                                                class="text-sm text-red-500 mt-2"
                                                transition:slide={{ duration: 200 }}
                                        >
                                            {#if plugin.status === "user_not_existing"}
                                                {$_("auth.email.user_not_found")}
                                            {:else if plugin.status === "error"}
                                                {$_("common.error")}
                                            {/if}
                                        </span>
    {/if}

    <Button
            class="mt-2"
            onclick={plugin.submit}
            disabled={plugin.status === "loading" || plugin.email === ""}
    >{$_("common.next")} {#if plugin.status === "loading"}<Loader />{:else}<ArrowRight />{/if}</Button>
</div>