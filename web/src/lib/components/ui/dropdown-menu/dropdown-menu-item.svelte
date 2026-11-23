<script lang="ts">
	import { cn } from "#lib/utils.js";
	import { DropdownMenu as DropdownMenuPrimitive } from "bits-ui";

	let {
		ref = $bindable(null),
		class: className,
		inset,
		variant = "default",
		href,
		children,
		...restProps
	}: DropdownMenuPrimitive.ItemProps & {
		inset?: boolean;
		variant?: "default" | "destructive";
		href?: string;
	} = $props();

	const itemClass = $derived(
		cn(
			"focus:bg-accent focus:text-accent-foreground data-[variant=destructive]:text-destructive data-[variant=destructive]:focus:bg-destructive/10 dark:data-[variant=destructive]:focus:bg-destructive/20 data-[variant=destructive]:focus:text-destructive data-[variant=destructive]:*:[svg]:text-destructive not-data-[variant=destructive]:focus:**:text-accent-foreground gap-2.5 rounded-xl px-3 py-2 text-sm [&_svg:not([class*='size-'])]:size-4 group/dropdown-menu-item relative flex items-center outline-hidden select-none data-disabled:pointer-events-none data-disabled:opacity-50 data-[inset]:pl-8 [&_svg]:pointer-events-none [&_svg]:shrink-0 cursor-pointer",
			className
		)
	);
</script>

{#if href}
	<DropdownMenuPrimitive.Item
		bind:ref
		data-slot="dropdown-menu-item"
		data-inset={inset}
		data-variant={variant}
		class={itemClass}
		{...restProps}
	>
		{#snippet child({ props })}
			<a {href} {...props}>
				{@render children?.()}
			</a>
		{/snippet}
	</DropdownMenuPrimitive.Item>
{:else}
	<DropdownMenuPrimitive.Item
		bind:ref
		data-slot="dropdown-menu-item"
		data-inset={inset}
		data-variant={variant}
		class={itemClass}
		{...restProps}
	>
		{@render children?.()}
	</DropdownMenuPrimitive.Item>
{/if}
