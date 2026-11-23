import adapter from '@sveltejs/adapter-node';
import tailwindcss from '@tailwindcss/vite';
import {sveltekit} from '@sveltejs/kit/vite';
import {svelteTesting} from '@testing-library/svelte/vite';
import {defineConfig} from 'vitest/config';

export default defineConfig({
    plugins: [
        tailwindcss(),
        sveltekit({
            compilerOptions: {
                // Force runes mode for the project, except for libraries. Can be removed in svelte 6.
                runes: ({ filename }) => filename.split(/[/\\]/).includes('node_modules') ? undefined : true
            },
            inspector: true,
            adapter: adapter()
        }),
        svelteTesting()
    ],
    server: {
        allowedHosts: true,
        host: "0.0.0.0",
        strictPort: true,
        port: 20415,
    },
    test: {
        environment: "jsdom",
        include: ["src/**/*.test.ts"],
        setupFiles: ["src/vitest-setup.ts"],
    },
});
