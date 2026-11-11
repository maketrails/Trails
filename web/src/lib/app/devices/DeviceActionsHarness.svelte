<script lang="ts">
    import DeviceActions from "./DeviceActions.svelte";

    /** Mirrors the device page: the id is read off an object that updates replace. */
    let {device}: {device: {id: string; battery: number}} = $props();
</script>

<DeviceActions deviceId={device.id}/>
