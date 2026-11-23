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
    import {DeviceRepository} from "#lib/api/devices/device_repository.ts";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(),
        deviceId,
        deviceName,
    }: {
        open: boolean,
        deviceId: string,
        deviceName: string,
    } = $props();

    let deleting = $state(false);

    async function confirmDelete() {
        if (deleting) return;
        deleting = true;
        const ok = await DeviceRepository.remove(deviceId);
        deleting = false;
        if (ok) {
            open = false;
            // The device is gone; return to the device list.
            await goto("/");
        }
    }
</script>

<Dialog bind:open={open}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("devices.dialogs.delete.title")}</DialogTitle>
            <DialogDescription>
                {$_("devices.dialogs.delete.description", {values: {name: deviceName}})}
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
