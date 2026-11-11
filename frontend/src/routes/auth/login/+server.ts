import { error } from '@sveltejs/kit';
import { startLogin } from '#lib/server/auth.js';
import { redirectAuth } from '#lib/server/auth-redirect.js';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ cookies }) => {
	let destination: URL;
	try { destination = await startLogin(cookies); }
	catch { error(503, 'Le service de connexion est indisponible. Réessayez dans quelques instants.'); }
	redirectAuth(destination);
};
