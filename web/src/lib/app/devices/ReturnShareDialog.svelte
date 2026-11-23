<script lang="ts">
    import {
        Dialog,
        DialogContent,
        DialogDescription,
        DialogFooter,
        DialogHeader,
        DialogTitle
    } from "#lib/components/ui/dialog/index.ts";
    import {Button} from "#lib/components/ui/button/index.ts";
    import {CircleNotchIcon} from "phosphor-svelte";
    import {goto} from "$app/navigation";
    import {ShareRepository} from "#lib/api/shares/share_repository.ts";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(),
        shareId,
        homeserver,
    }: {
        open: boolean,
        shareId: string,
        // The share's origin homeserver; empty for a same-server share.
        homeserver: string,
    } = $props();

    let returning = $state(false);

    async function confirmReturn() {
        if (returning) return;
        returning = true;
        const ok = await ShareRepository.returnShare(shareId, homeserver);
        returning = false;
        if (ok) {
            open = false;
            // The share is no longer saved; return to the device list.
            await goto("/");
        }
    }
</script>

<Dialog bind:open={open}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("shares.dialogs.return.title")}</DialogTitle>
            <DialogDescription>
                {$_("shares.dialogs.return.description")}
            </DialogDescription>
        </DialogHeader>

        <DialogFooter>
            <Button variant="secondary" onclick={() => open = false} disabled={returning}>{$_("common.cancel")}</Button>
            <Button onclick={confirmReturn} disabled={returning}>
                {#if returning}
                    <CircleNotchIcon class="size-4 animate-spin" />
                {/if}
                {$_("shares.dialogs.return.confirm")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>
