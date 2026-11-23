<script lang="ts">
    import {currentUser} from "#lib/state/current_user.ts";
    import {Button} from "#lib/components/ui/button/index.ts";
    import DeviceList from "#lib/app/devices/DeviceList.svelte";
    import {_} from "svelte-i18n";
</script>

{#if $currentUser === null}
    <div class="flex flex-col items-center justify-center h-full">
        <h1 class="text-4xl font-light">{$_("auth.welcome")}</h1>
        <Button class="mt-4" href="/api/v1/auth/webapp-authorization">{$_("auth.login")}</Button>
    </div>

{:else}
    <DeviceList />
{/if}
