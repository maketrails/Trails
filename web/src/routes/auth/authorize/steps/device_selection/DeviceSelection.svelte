<script lang="ts">
    import type {DeviceSelectPluginInstance, DeviceSelectSnippet} from "./types.ts";
    import {type FlowUserState, useAuthentiktContext} from "@julius-babies/authentikt-svelte";
    import {DeviceSelectionPlugin} from "./DeviceSelectionPlugin.svelte.ts";
    import {Button} from "$lib/components/ui/button";
    import NewDeviceInfoDialog from "./NewDeviceInfoDialog.svelte";
    import dayjs from "$lib/dayjs";
    import {_} from "svelte-i18n";

    let {
        children,
        plugin: externalPlugin,
        user: _user,
    }: {
        children?: DeviceSelectSnippet;
        plugin?: DeviceSelectPluginInstance;
        user?: FlowUserState | null;
    } = $props();

    const authentikt = useAuthentiktContext();
    const namespace = "trails/device-selection";

    const selfPlugin = authentikt.registerPlugin<DeviceSelectPluginInstance>(
        namespace,
        DeviceSelection,
        (auth, ns) => new DeviceSelectionPlugin(auth, ns)
    );

    const plugin = $derived(externalPlugin ?? selfPlugin);

    let showNewDeviceDialog = $state(false);
</script>

<script lang="ts" module>
    import DeviceSelection from "./DeviceSelection.svelte";
</script>

{#if plugin.isActive}
    {#if children}
        {@render children(plugin)}
    {:else if plugin.data}
        <div>
            <div class="leading-tight">
                {#if plugin.data.options.length > 1}
                    {$_("device_selection.multiple")}
                {:else}
                    {$_("device_selection.single", {
                        values: {
                            manufacturer: plugin.data.options[0].manufacturer,
                            model: plugin.data.options[0].friendly_name,
                        },
                    })}
                {/if}
                <div class="pt-1">
                    {$_("device_selection.location_note")}
                </div>
            </div>
            <div class="flex flex-col w-full mt-4 border border-zinc-200 rounded-lg overflow-y-auto">
                {#each plugin.data.options as option, index (option.device_id)}
                    {@const registeredAt = dayjs(option.created_at * 1000)}
                    <button
                            class="p-4 border-t-zinc-200 flex flex-row gap-4 items-center justify-start bg-zinc-50 transition-colors hover:bg-zinc-100 duration-75 cursor-pointer text-left"
                            class:border-t={index > 0}
                            class:cursor-events-none={plugin.data.options.length === 1}
                            onclick={() => plugin.selectDevice(option.device_id)}
                            disabled={plugin.data.options.length === 1}
                    >
                        <div class="h-full flex flex-row">
                            <img src="/api/v1/devices/image/{option.manufacturer}-{option.model}" alt="" class="w-16 h-16 object-contain">
                        </div>
                        <div class="flex flex-col w-full items-start">
                            <span class="test-lg font-semibold text-zinc-800">{option.manufacturer} {option.friendly_name} <span class="text-sm">({option.model})</span></span>
                            {#if option.display_name !== option.manufacturer + " " + option.friendly_name}
                                <span class="text-zinc-500 text-sm">{option.display_name}</span>
                            {/if}
                            <!-- L/LT are dayjs' localized date/time patterns and follow the active locale. -->
                            <span class="pt-1 font-light text-xs">{$_("device_selection.registered", {
                                values: {date: registeredAt.format("L"), time: registeredAt.format("LT")},
                            })}</span>
                        </div>
                    </button>
                {/each}
            </div>

            <div class="flex flex-row gap-2 items-center justify-center mt-4 flex-wrap">
                {#if plugin.data.options.length === 1 && plugin.data}
                    <Button
                            onclick={() => plugin.selectDevice(plugin.data!.options[0].device_id)}
                            variant="default"
                            class="flex flex-1"
                    >{$_("device_selection.use_this")}</Button>
                {/if}
                <Button
                        variant="outline"
                        class="flex flex-1"
                        onclick={() => showNewDeviceDialog = true}
                >{$_("device_selection.register_new")}</Button>
            </div>
        </div>
    {:else}
        <p>{$_("device_selection.loading")}</p>
    {/if}

    {#if showNewDeviceDialog}
        <NewDeviceInfoDialog
                onDismiss={() => showNewDeviceDialog = false}
                onSubmitName={(name) => plugin.newDevice(name)}
        />
    {/if}
{/if}