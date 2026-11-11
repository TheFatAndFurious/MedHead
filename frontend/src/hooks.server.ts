import { error } from '@sveltejs/kit';
import type { Handle } from '@sveltejs/kit/hooks';
import { authenticate } from '#lib/server/auth.js';
import { authConfig, sameOriginRequest } from '#lib/server/auth-config.js';

export const handle: Handle = async ({ event, resolve }) => {
	const settings = authConfig();
	if (!sameOriginRequest(event.request, settings.origin)) {
		error(403, 'Origine de la demande refusée.');
	}
	event.locals.user = null;
	event.locals.accessToken = null;
	if (!event.url.pathname.startsWith('/auth/')) {
		try {
			const session = await authenticate(event.cookies);
			event.locals.user = session?.user ?? null;
			event.locals.accessToken = session?.accessToken ?? null;
		} catch {
			error(503, 'La connexion est temporairement indisponible. Réessayez dans quelques instants.');
		}
	}
	const response = await resolve(event);
	response.headers.set('Cache-Control', 'private, no-store');
	response.headers.set('Referrer-Policy', 'no-referrer');
	return response;
};
