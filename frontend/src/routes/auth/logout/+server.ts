import { error } from '@sveltejs/kit';
import { endSession } from '#lib/server/auth.js';
import { redirectAuth } from '#lib/server/auth-redirect.js';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ cookies }) => {
	let destination: string;
	try { destination = await endSession(cookies); }
	catch { error(503, 'La déconnexion n’a pas pu être confirmée. Réessayez dans quelques instants.'); }
	redirectAuth(destination);
};
