import { defineEnvVars } from '@sveltejs/kit/env';

/**
 * Environment variables of the web app.
 */
export const variables = defineEnvVars({
	/**
	 * Public base URL of the Trails server, e.g. `https://trails.example.com`.
	 *
	 * Dynamic on purpose: `deploy/entrypoint.bash` writes it to `.env` when the container
	 * starts, so it is not available at build time (and in tests) and must not be required.
	 */
	PUBLIC_BASE_URL: { public: true, schema: (input) => input }
});
