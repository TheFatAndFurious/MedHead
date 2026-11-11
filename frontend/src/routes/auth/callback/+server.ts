import { error, redirect } from '@sveltejs/kit';
import { finishLogin } from '#lib/server/auth.js';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ cookies, url }) => {
	try { await finishLogin(cookies, url); }
	catch { error(400, 'Connexion non validée ou expirée. Revenez à l’accueil pour vous reconnecter.'); }
	redirect(303, '/');
};
