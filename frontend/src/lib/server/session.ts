export type User = { sub: string; name: string; roles: string[] };
export type Session = {
	user: User;
	accessToken: string;
	refreshToken: string;
	accessTokenExpiresAt: number;
	expiresAt: number;
};
export type Tokens = { accessToken: string; refreshToken: string; expiresIn: number; user?: User };

export interface SessionStore {
	read(id: string): Promise<Session | null>;
	write(id: string, session: Session, existingOnly: boolean): Promise<boolean>;
	remove(id: string): Promise<void>;
	lock<T>(id: string, operation: () => Promise<T>): Promise<T>;
}

export function userFromClaims(claims: Record<string, unknown>): User {
	if (typeof claims.sub !== 'string' || !claims.sub) throw new Error('Missing subject');
	return {
		sub: claims.sub,
		name: typeof claims.name === 'string' ? claims.name
			: typeof claims.preferred_username === 'string' ? claims.preferred_username : claims.sub,
		roles: Array.isArray(claims.roles)
			? claims.roles.filter((role): role is string => role === 'LECTEUR' || role === 'REGULATEUR') : []
	};
}

export function hasRole(user: User | null, role: string): boolean {
	return user?.roles.includes(role) ?? false;
}

// Refresh under a shared lock, and use SET XX when saving: a concurrent logout
// must never recreate a deleted session. Absolute expiry never moves forward.
export async function resolveSession(
	id: string,
	store: SessionStore,
	refresh: (token: string) => Promise<Tokens | null>,
	now: () => number = Date.now
): Promise<Session | null> {
	const session = await store.read(id);
	if (!session) return null;
	if (session.expiresAt <= now()) {
		await store.remove(id);
		return null;
	}
	if (session.accessTokenExpiresAt > now() + 30_000) return session;
	return store.lock(id, async () => {
		const current = await store.read(id);
		if (!current) return null;
		if (current.expiresAt <= now()) {
			await store.remove(id);
			return null;
		}
		if (current.accessTokenExpiresAt > now() + 30_000) return current;
		const tokens = await refresh(current.refreshToken);
		if (!tokens) {
			await store.remove(id);
			return null;
		}
		const updated = {
			...current,
			user: tokens.user ?? current.user,
			accessToken: tokens.accessToken,
			refreshToken: tokens.refreshToken,
			accessTokenExpiresAt: now() + tokens.expiresIn * 1000
		};
		return await store.write(id, updated, true) ? updated : null;
	});
}
