<script lang="ts">
    import { ArrowLeftIcon, DotsThreeVerticalIcon, TrashIcon, PencilIcon } from "phosphor-svelte";
    import {DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem} from "#lib/components/ui/dropdown-menu/index.ts";
    import Button from "#lib/components/ui/button/button.svelte";
    import DeleteShareDialog from "#lib/app/emitted-shares/DeleteShareDialog.svelte";
    import RenameShareDialog from "#lib/app/emitted-shares/RenameShareDialog.svelte";
    import type {EmittedShare} from "#lib/state/webapp_socket.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        share,
    }: {
        // The share this header belongs to. Absent while the socket is still
        // loading it (or when it is gone) — the menu needs a share to act on, so
        // it only appears once there is one.
        share?: EmittedShare | null;
    } = $props();

    let showDeleteDialog = $state(false);
    let showRenameDialog = $state(false)
</script>

<div class="flex flex-row items-center gap-2 justify-between px-4">
    <a
            href="/"
            class="flex flex-row grow items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
        <ArrowLeftIcon class="size-4"/>
        {$_("emitted_shares.title")}
    </a>

    {#if share}
        <DropdownMenu>
            <DropdownMenuTrigger>
                <Button variant="ghost" size="icon" class="size-8">
                    <DotsThreeVerticalIcon/>
                </Button>
            </DropdownMenuTrigger>

            <DropdownMenuContent>
                <DropdownMenuGroup>
                    <DropdownMenuItem class="text-destructive" onclick={() => showDeleteDialog = true}>
                        <TrashIcon/>
                        {$_("common.delete")}
                    </DropdownMenuItem>

                    <DropdownMenuItem onclick={() => showRenameDialog = true}>
                        <PencilIcon/>
                        {$_("common.rename")}
                    </DropdownMenuItem>
                </DropdownMenuGroup>
            </DropdownMenuContent>
        </DropdownMenu>
    {/if}
</div>

{#if share}
    <DeleteShareDialog
            shareId={share.id}
            shareName={share.name}
            bind:open={showDeleteDialog}
    />

    <RenameShareDialog
            shareId={share.id}
            currentShareName={share.name}
            bind:open={showRenameDialog}
    />
{/if}
