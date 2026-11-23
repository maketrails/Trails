<script lang="ts">
    import {ArrowLeftIcon, DotsThreeVerticalIcon, PencilIcon, TrashIcon} from "phosphor-svelte";
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuGroup,
        DropdownMenuItem,
        DropdownMenuTrigger
    } from "#lib/components/ui/dropdown-menu/index.ts";
    import {Button} from "#lib/components/ui/button/index.ts";
    import RenameDeviceDialog from "#lib/app/devices/RenameDeviceDialog.svelte";
    import DeleteDeviceDialog from "#lib/app/devices/DeleteDeviceDialog.svelte";
    import ReturnShareDialog from "#lib/app/devices/ReturnShareDialog.svelte";
    import type {Device} from "#lib/state/webapp_socket.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        device,
        shareId,
        homeserver = "",
    }: {
        // The device this header belongs to, or undefined for a shared device
        // (which can't be renamed or deleted, only returned).
        device?: Device;
        // For a shared device: the active-share id and its origin homeserver
        // (empty for a same-server share), needed to return the share.
        shareId?: string;
        homeserver?: string;
    } = $props();

    // Only own devices carry a Device; shared ones don't.
    let isOwnDevice = $derived(device != null);

    let showRenameDialog = $state(false);
    let showDeleteDialog = $state(false);
    let showReturnDialog = $state(false);
</script>

<div class="flex flex-row items-center gap-2 justify-between px-4">
    <a
            href="/"
            class="flex flex-row grow items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
        <ArrowLeftIcon class="size-4"/>
        {$_("devices.title")}
    </a>

    <DropdownMenu>
        <DropdownMenuTrigger>
            <Button variant="ghost" size="icon" class="size-8">
                <DotsThreeVerticalIcon/>
            </Button>
        </DropdownMenuTrigger>

        <DropdownMenuContent>
            <DropdownMenuGroup>
                {#if !isOwnDevice}
                    <DropdownMenuItem onclick={() => showReturnDialog = true}>{$_("shares.dialogs.return.title")}</DropdownMenuItem>
                {:else}
                    <DropdownMenuItem class="text-destructive" onclick={() => showDeleteDialog = true}>
                        <TrashIcon/>
                        {$_("common.delete")}
                    </DropdownMenuItem>

                    <DropdownMenuItem onclick={() => showRenameDialog = true}>
                        <PencilIcon/>
                        {$_("common.rename")}
                    </DropdownMenuItem>
                {/if}
            </DropdownMenuGroup>
        </DropdownMenuContent>
    </DropdownMenu>
</div>

{#if device}
    <RenameDeviceDialog
            deviceId={device.id}
            currentDeviceName={device.hasCustomName ? device.name : ""}
            bind:open={showRenameDialog}
    />

    <DeleteDeviceDialog
            deviceId={device.id}
            deviceName={device.name}
            bind:open={showDeleteDialog}
    />
{:else if shareId}
    <ReturnShareDialog
            shareId={shareId}
            homeserver={homeserver}
            bind:open={showReturnDialog}
    />
{/if}
