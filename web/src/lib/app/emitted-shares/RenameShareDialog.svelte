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
    import {Input} from "#lib/components/ui/input/index.ts";
    import {CircleNotchIcon} from "phosphor-svelte";
    import {ShareRepository, type RenameShareResult} from "#lib/api/shares/share_repository.ts";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(),
        shareId,
        currentShareName,
    }: {
        open: boolean,
        shareId: string,
        currentShareName: string,
    } = $props();

    let name = $state("");
    let error = $state<RenameShareResult | null>(null);

    $effect(() => {
        if (open) {
            name = currentShareName;
            error = null;
        }
    })

    let inputElement = $state<HTMLInputElement | null>(null);

    $effect(() => {
        if (open && inputElement) inputElement.focus();
    })

    let saving = $state(false);

    async function save() {
        if (saving) return;
        saving = true;
        error = null;
        const result = await ShareRepository.renameEmittedShare(shareId, name.trim());
        saving = false;
        if (result === "ok") open = false;
        else error = result;
    }
</script>

<Dialog bind:open={open}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("emitted_shares.dialogs.rename.title")}</DialogTitle>
            <DialogDescription>{$_("emitted_shares.dialogs.rename.description")}</DialogDescription>
        </DialogHeader>

        <div class="flex flex-col gap-1.5">
            <Input
                    bind:value={name}
                    bind:ref={inputElement}
                    type="text"
                    placeholder={$_("emitted_shares.dialogs.rename.placeholder")}
                    aria-invalid={error != null}
                    onkeydown={(e) => { if (e.key === "Enter") save(); }}
            />

            {#if error === "name-taken"}
                <span class="text-sm text-destructive">{$_("emitted_shares.dialogs.name.taken")}</span>
            {:else if error === "name-blank"}
                <span class="text-sm text-destructive">{$_("emitted_shares.dialogs.name.blank")}</span>
            {:else if error === "error"}
                <span class="text-sm text-destructive">{$_("emitted_shares.dialogs.rename.failed")}</span>
            {/if}
        </div>

        <DialogFooter>
            <Button variant="secondary" onclick={() => open = false} disabled={saving}>{$_("common.cancel")}</Button>
            <Button onclick={save} disabled={saving}>
                {#if saving}
                    <CircleNotchIcon class="size-4 animate-spin" />
                {/if}
                {$_("common.save")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>
