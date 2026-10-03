<script lang="ts">
    import {
        Authentikt,
        AuthentiktDebug,
        type AuthentiktConfiguration,
        DoneRenderer,
        OIDCRenderer,
        useAuthentiktContext
    } from "@julius-babies/authentikt-svelte";
    import LinkFlow from "./steps/init/LinkFlow.svelte";
    import DeviceSelection from "./steps/device_selection/DeviceSelection.svelte";
    import EmailUserSelection from "./steps/email/EmailUserSelection.svelte";
    import Password from "./steps/password/Password.svelte";
    import { env } from '$env/dynamic/public';
    import {_} from "svelte-i18n";

    const config: AuthentiktConfiguration = {
        baseUrl: env.PUBLIC_BASE_URL + "/api/v1/auth/authentikt/",
        // debug: {show_overlay: false},
        debug: false
    }

</script>

<div class="relative flex flex-col w-full h-full">
    <Authentikt
            {config}
    >
        {@const authentikt = useAuthentiktContext()}

        {#if !!config.debug}
            <div class="relative h-80 overflow-y-auto">
                <AuthentiktDebug authentikt={authentikt} />
            </div>
        {/if}

        <div class="w-full h-full flex flex-col items-center justify-center">
            <div class="flex flex-col items-center w-full h-full overflow-y-auto px-2">
                <span class="text-6xl font-extralight pt-8">Trails</span>
                <span class="text-base font-light text-zinc-600 pt-1 pb-2">{$_("auth.subtitle")}</span>

                <div class="flex grow w-full pt-4">
                    {#if !authentikt.currentFlow}
                        <LinkFlow />
                    {:else}
                        <DeviceSelection />
                        <EmailUserSelection />
                        <Password />
                        <OIDCRenderer />
                        <DoneRenderer>
                            {#snippet children(plugin)}
                                <div class="flex flex-col items-center justify-center w-full h-full gap-4">
                                    <span class="text-xl font-semibold pb-1">{$_("auth.success")}</span>
                                    {#if plugin.result?.type === "redirect"}
                                        <div>
                                            {$_("auth.redirect.hint")}
                                            <a href={plugin.result.to} class="underline">{$_("auth.redirect.link")}</a>
                                        </div>
                                    {/if}
                                </div>
                            {/snippet}
                        </DoneRenderer>
                    {/if}
                </div>
                <!-- "Authentikt" is a product name, so only the lead-in is translated. -->
                <span class="text-xs text-zinc-400">{$_("auth.powered_by")} <a href="https://github.com/Julius-Babies/authentikt" class="underline" target="blank" rel="noreferrer">Authentikt</a>.</span><br />

                <!-- TODO: Remove for production -->
                <span class="text-xs text-zinc-400"><a data-sveltekit-reload href="/api/v1/auth/app-authorization?device_manufacturer=Google&device_model=panther" class="underline">{$_("auth.restart")}</a></span>
            </div>
        </div>
    </Authentikt>
</div>
