const env = process.env;

export function sameOriginRequest(request: Request, origin: string): boolean {
	return ['GET', 'HEAD', 'OPTIONS'].includes(request.method) || request.headers.get('origin') === origin;
}

export function authConfig() {
	const origin = new URL(env.MEDHEAD_PUBLIC_ORIGIN ?? 'http://localhost:5173');
	const issuer = new URL(env.KEYCLOAK_ISSUER_URI ?? 'http://localhost:8180/realms/medhead');
	const allowHttp = env.AUTH_ALLOW_HTTP === 'true';
	if (origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) {
		throw new Error('MEDHEAD_PUBLIC_ORIGIN must be an origin');
	}
	if (!allowHttp && (origin.protocol !== 'https:' || issuer.protocol !== 'https:')) {
		throw new Error('HTTPS is required outside explicit local development');
	}
	if (!env.KEYCLOAK_CLIENT_SECRET) throw new Error('KEYCLOAK_CLIENT_SECRET is required');
	const secure = origin.protocol === 'https:';
	return {
		origin: origin.origin,
		issuer,
		internalOrigin: env.KEYCLOAK_INTERNAL_ORIGIN,
		clientId: 'medhead-web',
		clientSecret: env.KEYCLOAK_CLIENT_SECRET,
		allowHttp,
		cookie: secure ? '__Host-medhead_session' : 'medhead_session',
		loginCookie: secure ? '__Host-medhead_login' : 'medhead_login',
		cookieOptions: { path: '/', httpOnly: true, secure, sameSite: 'lax' as const },
		sessionSeconds: 8 * 60 * 60
	};
}
