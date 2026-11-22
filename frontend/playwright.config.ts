import { defineConfig, devices } from '@playwright/test';

// The Keycloak redirect URIs and SvelteKit's CSRF check only accept the public
// origin of the demo stack: keep "localhost", never 127.0.0.1.
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
const ci = Boolean(process.env.CI);

export default defineConfig({
	testDir: './e2e',
	globalSetup: './e2e/global-setup.ts',
	// The scenarios share one stack and consume demo beds: run them one at a time.
	fullyParallel: false,
	workers: 1,
	forbidOnly: ci,
	retries: ci ? 1 : 0,
	timeout: 60_000,
	expect: { timeout: 10_000 },
	reporter: [['list'], ['html', { open: 'never' }]],
	use: {
		baseURL,
		locale: 'fr-FR',
		trace: 'on-first-retry',
		screenshot: 'only-on-failure'
	},
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
