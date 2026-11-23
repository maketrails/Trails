<script lang="ts">
    import {Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter} from "#lib/components/ui/dialog/index.ts";
    import Button from "#lib/components/ui/button/button.svelte";
    import { CircleNotchIcon } from "phosphor-svelte";
    import {goto} from "$app/navigation";
    import {ShareRepository} from "#lib/api/shares/share_repository.ts";
    import {_} from "svelte-i18n";

    let {
      open = $bindable(),
      shareId,
      shareName,
    }: {
      open: boolean,
      shareId: string,
      shareName: string,
    } = $props();

    let deleting = $state(false);

    async function confirmDelete() {
        if (deleting) return;
        deleting = true;
        const ok = await ShareRepository.removeEmittedShare(shareId);
        deleting = false;
        if (ok) {
            open = false;
            // The share is gone; return to the overview.
            await goto("/");
        }
    }
</script>

<Dialog bind:open={open}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("emitted_shares.dialogs.delete.title")}</DialogTitle>
            <DialogDescription>
                {$_("emitted_shares.dialogs.delete.description", {values: {name: shareName}})}
            </DialogDescription>
        </DialogHeader>

        <DialogFooter>
            <Button variant="secondary" onclick={() => open = false} disabled={deleting}>{$_("common.cancel")}</Button>
            <Button variant="destructive" onclick={confirmDelete} disabled={deleting}>
                {#if deleting}
                    <CircleNotchIcon class="size-4 animate-spin" />
                {/if}
                {$_("common.delete")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>
