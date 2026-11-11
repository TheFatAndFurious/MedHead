import { redirect } from '@sveltejs/kit';
import { authConfig } from './auth-config.js';

export function redirectAuth(destination: string | URL): never {
	const settings = authConfig();
	redirect(303, destination, { external: [settings.issuer.origin, settings.origin] });
}
