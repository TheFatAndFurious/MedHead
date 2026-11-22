import { expect, type Page } from '@playwright/test';

// Demo accounts imported with the medhead realm (see .env.example).
export const accounts = {
	regulateur: {
		username: 'regulateur',
		password: process.env.KEYCLOAK_REGULATEUR_PASSWORD ?? 'regulateur_demo'
	},
	lecteur: {
		username: 'lecteur',
		password: process.env.KEYCLOAK_LECTEUR_PASSWORD ?? 'lecteur_demo'
	}
} as const;

const keycloakOrigin = new URL(process.env.KEYCLOAK_URL ?? 'http://localhost:8180').origin;

/** Matches the pages of the medhead realm (login form, logout confirmation). */
export const isKeycloakPage = (url: URL) =>
	url.origin === keycloakOrigin && url.pathname.startsWith('/realms/medhead/');

/** Signs in through the Keycloak login page and returns to the MedHead home page. */
export async function signIn(page: Page, account: { username: string; password: string }) {
	await page.goto('/');
	await page.getByRole('navigation', { name: 'Compte utilisateur' })
		.getByRole('link', { name: 'Se connecter' })
		.click();
	await page.waitForURL(isKeycloakPage);
	// Field ids are stable across Keycloak login themes and locales.
	await page.locator('#username').fill(account.username);
	await page.locator('#password').fill(account.password);
	await page.locator('#kc-login').click();
	await expect(page).toHaveURL('/');
	await expect(page.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
}

/** Selects a demo location and a specialty in the reservation form. */
export async function chooseRequest(page: Page, location: string, specialty: string) {
	await page.getByLabel('Localisation de l’urgence').selectOption({ label: location });
	await page.getByLabel('Spécialité médicale').selectOption({ label: specialty });
}
