import { expect, test } from '@playwright/test';
import { accounts, chooseRequest, signIn } from './auth.js';

test.describe('Régulateur', () => {
	test.beforeEach(async ({ page }) => {
		await signIn(page, accounts.regulateur);
	});

	test('réserve un lit dans l’hôpital le plus proche ayant la spécialité', async ({ page }) => {
		await chooseRequest(page, 'Secteur Westminster', 'Cardiologie');
		await page.getByRole('button', { name: 'Réserver un lit' }).click();

		const result = page.locator('section.result');
		await expect(result.getByText('Dernière réservation confirmée')).toBeVisible();
		await expect(result.getByRole('heading', { level: 2 })).not.toBeEmpty();
		const field = (term: string) => result.locator('dt', { hasText: term }).locator('+ dd');
		await expect(field('Départ')).toHaveText('Secteur Westminster');
		await expect(field('Spécialité')).toHaveText('Cardiologie');
		await expect(field('Distance à vol d’oiseau')).toHaveText(/^\d+(,\d+)? km$/);
		await expect(field('Référence')).toHaveText(
			/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
		);
	});

	test('est prévenu quand aucun hôpital ne propose la spécialité', async ({ page }) => {
		// No demo hospital offers ALLERGY: the API answers 404.
		await chooseRequest(page, 'Secteur Westminster', 'Allergie');
		await page.getByRole('button', { name: 'Réserver un lit' }).click();

		await expect(page.getByRole('alert')).toHaveText(
			'Aucun hôpital ne dispose d’un lit pour cette spécialité.'
		);
		await expect(page.locator('section.result')).toHaveCount(0);
	});
});
