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
    import {DeviceRepository} from "#lib/api/devices/device_repository.ts";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(),
        deviceId,
        currentDeviceName,
    }: {
        open: boolean,
        deviceId: string,
        currentDeviceName: string,
    } = $props();

    let name = $state("");
    $effect(() => {
        if (open) name = currentDeviceName;
    })

    let inputElement = $state<HTMLInputElement | null>(null);

    $effect(() => {
        if (open && inputElement) inputElement.focus();
    })

    let saving = $state(false);

    async function save() {
        if (saving) return;
        saving = true;
        // An empty name clears the custom name (server falls back to the model name).
        const ok = await DeviceRepository.rename(deviceId, name.trim());
        saving = false;
        if (ok) open = false;
    }
</script>

<Dialog bind:open={open}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("devices.dialogs.rename.title")}</DialogTitle>
            <DialogDescription>{$_("devices.dialogs.rename.description")}</DialogDescription>
        </DialogHeader>

        <Input
                bind:value={name}
                bind:ref={inputElement}
                type="text"
                placeholder={$_("devices.dialogs.rename.placeholder")}
                onkeydown={(e) => { if (e.key === "Enter") save(); }}
        />

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
