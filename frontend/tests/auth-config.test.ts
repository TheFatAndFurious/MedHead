import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authConfig, sameOriginRequest } from '../src/lib/server/auth-config.ts';

test('production cookies are opaque, HttpOnly, Secure and restricted to the host', (t) => {
	const previous = { ...process.env };
	t.after(() => {
		for (const key of Object.keys(process.env)) if (!(key in previous)) delete process.env[key];
		Object.assign(process.env, previous);
	});
	process.env.MEDHEAD_PUBLIC_ORIGIN = 'https://medhead.example.test';
	process.env.KEYCLOAK_ISSUER_URI = 'https://identity.example.test/realms/medhead';
	process.env.KEYCLOAK_CLIENT_SECRET = 'test-only-secret';
	delete process.env.AUTH_ALLOW_HTTP;
	const config = authConfig();
	assert.equal(config.cookie, '__Host-medhead_session');
	assert.deepEqual(config.cookieOptions, { path: '/', secure: true, httpOnly: true, sameSite: 'lax' });
	process.env.MEDHEAD_PUBLIC_ORIGIN = 'http://localhost:5173';
	assert.throws(() => authConfig(), /HTTPS/);
	process.env.AUTH_ALLOW_HTTP = 'true';
	assert.equal(authConfig().cookieOptions.secure, false);
	delete process.env.KEYCLOAK_CLIENT_SECRET;
	assert.throws(() => authConfig(), /SECRET/);
});

test('browser mutations require the exact configured origin, including JSON and logout requests', () => {
	const origin = 'https://medhead.example.test';
	const request = (source?: string) => new Request(`${origin}/auth/logout`, {
		method: 'POST', headers: source ? { origin: source, 'Content-Type': 'application/json' } : {}
	});
	assert.equal(sameOriginRequest(request(origin), origin), true);
	assert.equal(sameOriginRequest(request('https://attacker.example.test'), origin), false);
	assert.equal(sameOriginRequest(request('https://medhead.example.test.attacker.test'), origin), false);
	assert.equal(sameOriginRequest(request('null'), origin), false);
	assert.equal(sameOriginRequest(request(), origin), false);
	assert.equal(sameOriginRequest(new Request(origin), origin), true);
});
