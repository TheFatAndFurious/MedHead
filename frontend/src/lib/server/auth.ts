import * as oidc from 'openid-client';
import type { Cookies } from '@sveltejs/kit';
import { authConfig } from './auth-config.js';
import { consumeLogin, randomId, saveLogin, sessionStore, validId } from './auth-store.js';
import { resolveSession, userFromClaims, type Session, type Tokens } from './session.js';

let configuration: Promise<oidc.Configuration> | undefined;
export function oidcConfig() {
	if (!configuration) {
		const settings = authConfig();
		configuration = oidc.discovery(settings.issuer, settings.clientId, settings.clientSecret, undefined, {
			timeout: 5,
			execute: [oidc.enableNonRepudiationChecks, ...(settings.allowHttp ? [oidc.allowInsecureRequests] : [])],
			[oidc.customFetch]: async (input, options) => {
				const url = new URL(input.toString());
				if (settings.internalOrigin && url.origin === settings.issuer.origin) {
					const internal = new URL(settings.internalOrigin);
					url.protocol = internal.protocol;
					url.host = internal.host;
				}
				return fetch(url, options as RequestInit);
			}
		}).catch((error) => { configuration = undefined; throw error; });
	}
	return configuration;
}

export async function startLogin(cookies: Cookies) {
	const settings = authConfig();
	const config = await oidcConfig();
	const verifier = oidc.randomPKCECodeVerifier();
	const transaction = { verifier, state: oidc.randomState(), nonce: oidc.randomNonce() };
	const id = randomId();
	await saveLogin(id, transaction);
	cookies.set(settings.loginCookie, id, { ...settings.cookieOptions, maxAge: 600 });
	return oidc.buildAuthorizationUrl(config, {
		redirect_uri: `${settings.origin}/auth/callback`,
		scope: 'openid profile',
		code_challenge: await oidc.calculatePKCECodeChallenge(verifier),
		code_challenge_method: 'S256',
		state: transaction.state,
		nonce: transaction.nonce
	});
}

export async function finishLogin(cookies: Cookies, callbackUrl: URL) {
	const settings = authConfig();
	const transaction = await consumeLogin(cookies.get(settings.loginCookie));
	cookies.delete(settings.loginCookie, settings.cookieOptions);
	if (!transaction) throw new Error('Missing or expired login transaction');
	// Use a configured callback origin, never one reconstructed from untrusted headers.
	const callback = new URL('/auth/callback', settings.origin);
	callback.search = callbackUrl.search;
	const tokens = await oidc.authorizationCodeGrant(await oidcConfig(), callback, {
		pkceCodeVerifier: transaction.verifier,
		expectedState: transaction.state,
		expectedNonce: transaction.nonce,
		idTokenExpected: true
	});
	const claims = tokens.claims();
	if (!claims || !tokens.refresh_token || !tokens.expires_in) throw new Error('Incomplete token response');
	const id = randomId();
	const session: Session = {
		user: userFromClaims(claims),
		accessToken: tokens.access_token,
		refreshToken: tokens.refresh_token,
		accessTokenExpiresAt: Date.now() + tokens.expires_in * 1000,
		expiresAt: Date.now() + settings.sessionSeconds * 1000
	};
	if (!await sessionStore.write(id, session, false)) throw new Error('Could not create session');
	const previous = cookies.get(settings.cookie);
	if (previous) await sessionStore.remove(previous);
	cookies.set(settings.cookie, id, { ...settings.cookieOptions, maxAge: settings.sessionSeconds });
}

async function refresh(token: string): Promise<Tokens | null> {
	try {
		const tokens = await oidc.refreshTokenGrant(await oidcConfig(), token);
		if (!tokens.refresh_token || !tokens.expires_in) throw new Error('Incomplete refresh response');
		const claims = tokens.claims();
		return {
			accessToken: tokens.access_token, refreshToken: tokens.refresh_token, expiresIn: tokens.expires_in,
			...(claims ? { user: userFromClaims(claims) } : {})
		};
	} catch (error) {
		if (error instanceof oidc.ResponseBodyError && error.error === 'invalid_grant') return null;
		throw error;
	}
}

export async function authenticate(cookies: Cookies) {
	const settings = authConfig();
	const id = cookies.get(settings.cookie);
	if (!validId(id)) {
		if (id) cookies.delete(settings.cookie, settings.cookieOptions);
		return null;
	}
	const session = await resolveSession(id, sessionStore, refresh);
	if (!session) cookies.delete(settings.cookie, settings.cookieOptions);
	return session;
}

export async function endSession(cookies: Cookies) {
	const settings = authConfig();
	const id = cookies.get(settings.cookie);
	const session = id ? await sessionStore.read(id) : null;
	if (id) await sessionStore.remove(id);
	cookies.delete(settings.cookie, settings.cookieOptions);
	try {
		const config = await oidcConfig();
		if (session) await oidc.tokenRevocation(config, session.refreshToken, { token_type_hint: 'refresh_token' });
		// No ID/access/refresh token is sent through the browser. Keycloak may ask
		// the user to confirm SSO logout when no id_token_hint is supplied.
		return oidc.buildEndSessionUrl(config, {
			client_id: settings.clientId, post_logout_redirect_uri: `${settings.origin}/`
		}).href;
	} catch {
		return `${settings.origin}/?logout=local`;
	}
}
