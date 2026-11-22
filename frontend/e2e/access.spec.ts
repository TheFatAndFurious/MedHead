import { expect, test } from '@playwright/test';
import { accounts, isKeycloakPage, signIn } from './auth.js';

test('un lecteur consulte les données mais ne peut pas réserver', async ({ page, baseURL }) => {
	await signIn(page, accounts.lecteur);

	await expect(page.getByRole('navigation', { name: 'Compte utilisateur' })).toContainText('Lecture seule');
	await expect(page.getByText(
		'Votre compte dispose d’un accès en lecture seule. Le rôle Régulateur est nécessaire pour réserver un lit.'
	)).toBeVisible();
	await expect(page.getByLabel('Localisation de l’urgence')).toBeDisabled();
	await expect(page.getByLabel('Spécialité médicale')).toBeDisabled();
	await expect(page.getByRole('button', { name: 'Réserver un lit' })).toBeDisabled();

	// The server refuses the action as well, even when the disabled form is bypassed.
	const response = await page.request.post('/', {
		headers: { Origin: new URL(baseURL!).origin },
		form: {
			locationId: 'westminster',
			specialtyCode: 'CARDIOLOGY',
			idempotencyKey: crypto.randomUUID()
		}
	});
	expect(response.status()).toBe(403);
	expect(await response.text()).toContain('Le rôle Régulateur est nécessaire pour réserver un lit.');
});

test('un visiteur non connecté est invité à se connecter via Keycloak', async ({ page }) => {
	await page.goto('/');

	await expect(page.getByRole('heading', { name: 'Connectez-vous à MedHead' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Votre demande' })).toHaveCount(0);
	await expect(page.getByLabel('Localisation de l’urgence')).toHaveCount(0);

	await page.getByRole('navigation', { name: 'Compte utilisateur' })
		.getByRole('link', { name: 'Se connecter' })
		.click();
	await page.waitForURL(isKeycloakPage);
	await expect(page.locator('#username')).toBeVisible();
	await expect(page.locator('#password')).toBeVisible();
});

test('la déconnexion ferme la session MedHead et la session Keycloak', async ({ page }) => {
	await signIn(page, accounts.regulateur);

	await page.getByRole('button', { name: 'Se déconnecter' }).click();
	// Without an id_token_hint, Keycloak may ask to confirm the logout.
	const confirmLogout = page.locator('#kc-logout');
	const signedOut = page.getByRole('heading', { name: 'Connectez-vous à MedHead' });
	await expect(confirmLogout.or(signedOut)).toBeVisible();
	if (await confirmLogout.isVisible()) await confirmLogout.click();
	await expect(page).toHaveURL('/');

	await expect(page.getByRole('heading', { name: 'Connectez-vous à MedHead' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Se déconnecter' })).toHaveCount(0);
	await page.reload();
	await expect(page.getByRole('heading', { name: 'Connectez-vous à MedHead' })).toBeVisible();

	// The Keycloak SSO session is closed too: signing in again asks for credentials.
	await page.getByRole('navigation', { name: 'Compte utilisateur' })
		.getByRole('link', { name: 'Se connecter' })
		.click();
	await page.waitForURL(isKeycloakPage);
	await expect(page.locator('#username')).toBeVisible();
});
