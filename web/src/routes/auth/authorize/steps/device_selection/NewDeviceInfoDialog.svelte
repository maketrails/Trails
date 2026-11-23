<script lang="ts">
    import {
        Dialog,
        DialogContent,
        DialogDescription,
        DialogFooter,
        DialogHeader,
        DialogTitle
    } from "#lib/components/ui/dialog/index.ts";
    import {Alert, AlertDescription, AlertTitle} from "#lib/components/ui/alert/index.ts";
    import {TriangleAlert, Loader} from "@lucide/svelte";
    import {Input} from "#lib/components/ui/input/index.ts";
    import {Button} from "#lib/components/ui/button/index.ts";
    import {Field, FieldDescription, FieldError, FieldLabel, FieldSet} from "#lib/components/ui/field/index.ts";
    import {slide} from "svelte/transition";
    import type {NewDeviceNameResult} from "./DeviceSelectionPlugin.svelte.ts";
    import {_} from "svelte-i18n";

    /** Shortest name the server accepts for a new device. */
    const MIN_NAME_LENGTH = 5;

    let {
        onDismiss,
        onSubmitName,
    }: {
        onDismiss: () => void;
        onSubmitName: (name: string) => Promise<NewDeviceNameResult>;
    } = $props();
    const componentId = $props.id();

    let open = $state(true);

    let deviceName = $state("");

    let deviceNameInput: HTMLInputElement | null = $state(null);
    $effect(() => {
        if (deviceNameInput) deviceNameInput.focus();
    })

    let error = $state<"unknown_error" | "name_already_exists" | "name_too_short" | null>(null);
    let isLoading = $state(false);
    $effect(() => {
        deviceName;
        error = null;
    })

    function submit() {
        if (isLoading) return;
        if (deviceName.length < MIN_NAME_LENGTH) {
            error = "name_too_short";
            if (deviceNameInput) deviceNameInput.focus();
            return;
        }

        isLoading = true;
        onSubmitName(deviceName).then((result) => {
            isLoading = false;
            if (result === "unknown_error") error = "unknown_error";
            if (result === "name_already_exists") error = "name_already_exists";
        })
    }
</script>

<Dialog bind:open={open} onOpenChangeComplete={(to) => { if (!to) onDismiss(); }}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("device_selection.dialogs.new.title")}</DialogTitle>
            <DialogDescription>
                <Alert class="mt-2" variant="warning">
                    <TriangleAlert />
                    <AlertTitle>{$_("device_selection.dialogs.new.hint.title")}</AlertTitle>
                    <AlertDescription>
                        {$_("device_selection.dialogs.new.hint.description")}
                    </AlertDescription>
                </Alert>
            </DialogDescription>
        </DialogHeader>

        <form class="flex flex-col w-full" onsubmit={submit}>
            <FieldSet>
                <Field>
                    <FieldLabel
                            for="device-name-field-{componentId}"
                    >{$_("device_selection.dialogs.name.label")}</FieldLabel>
                    <Input
                            id="device-name-field-{componentId}"
                            placeholder={$_("device_selection.dialogs.name.placeholder")}
                            bind:value={deviceName}
                            bind:ref={deviceNameInput}
                    />
                    <FieldDescription>{$_("device_selection.dialogs.name.description")}</FieldDescription>
                    <div>
                        {#if error}
                            <div transition:slide>
                                <FieldError>
                                    {#if error === "name_too_short"}
                                        {$_("device_selection.dialogs.name.too_short", {values: {min: MIN_NAME_LENGTH}})}
                                    {:else if error === "name_already_exists"}
                                        {$_("device_selection.dialogs.name.taken")}
                                    {:else if error === "unknown_error"}
                                        {$_("common.unknown_error")}
                                    {/if}
                                </FieldError>
                            </div>
                        {/if}
                    </div>
                </Field>
            </FieldSet>
        </form>

        <DialogFooter>
            <Button
                    variant="secondary"
                    onclick={() => open = false}
            >{$_("common.cancel")}</Button>
            <Button
                    variant="default"
                    onclick={submit}
                    disabled={isLoading}
            >
                {#if isLoading}
                    <div class="pr-1" transition:slide={{axis: "x"}}>
                        <Loader class="animate-spin" />
                    </div>
                {/if}
                {$_("common.save")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>
