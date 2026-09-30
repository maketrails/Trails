import tailwindcss from '@tailwindcss/vite';
import {sveltekit} from '@sveltejs/kit/vite';
import {svelteTesting} from '@testing-library/svelte/vite';
import {defineConfig} from 'vitest/config';

export default defineConfig({
    plugins: [tailwindcss(), sveltekit(), svelteTesting()],
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
