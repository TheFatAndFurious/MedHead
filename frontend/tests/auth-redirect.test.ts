import { beforeEach, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { isRedirect } from '@sveltejs/kit';
import { redirectAuth } from '../src/lib/server/auth-redirect.ts';

let previous: NodeJS.ProcessEnv;
beforeEach(() => {
	previous = { ...process.env };
	process.env.MEDHEAD_PUBLIC_ORIGIN = 'http://localhost:5173';
	process.env.KEYCLOAK_ISSUER_URI = 'http://localhost:8180/realms/medhead';
	process.env.KEYCLOAK_CLIENT_SECRET = 'test-only-secret';
	process.env.AUTH_ALLOW_HTTP = 'true';
});
afterEach(() => {
	for (const key of Object.keys(process.env)) if (!(key in previous)) delete process.env[key];
	Object.assign(process.env, previous);
});

test('login and logout redirect to the configured Keycloak origin with status 303', () => {
	for (const endpoint of ['auth', 'logout']) {
		const destination = new URL(`http://localhost:8180/realms/medhead/protocol/openid-connect/${endpoint}`);
		destination.searchParams.set('client_id', 'medhead-web');
		assert.throws(() => redirectAuth(destination), (error) =>
			isRedirect(error) && error.status === 303 && error.location === destination.href);
	}
});

test('the local logout fallback and internal callback destination remain valid', () => {
	for (const destination of ['http://localhost:5173/?logout=local', '/']) {
		assert.throws(() => redirectAuth(destination), (error) =>
			isRedirect(error) && error.status === 303 && error.location === destination);
	}
});

test('auth redirects reject unconfigured origins, other ports and script URLs', () => {
	for (const destination of [
		'https://attacker.example.test/',
		'http://localhost:8181/',
		'http://localhost.attacker.example.test:8180/',
		'http://localhost:8180@attacker.example.test/',
		'javascript:alert(1)'
	]) {
		assert.throws(() => redirectAuth(destination), (error) =>
			error instanceof Error && !isRedirect(error) && error.message.includes('redirect_external_not_in_allowlist'));
	}
});
