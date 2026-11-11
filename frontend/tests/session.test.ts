import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveSession, userFromClaims, hasRole, type Session, type SessionStore } from '../src/lib/server/session.ts';

const now = 1_800_000_000_000;
const session = (): Session => ({
	user: { sub: 'alice', name: 'Alice', roles: ['REGULATEUR'] },
	accessToken: 'access-token', refreshToken: 'refresh-token',
	accessTokenExpiresAt: now + 300_000, expiresAt: now + 28_800_000
});

function store(initial: Session | null): SessionStore {
	let value = initial;
	let pending: Promise<unknown> = Promise.resolve();
	return {
		async read() { return value; },
		async write(_id, updated, existingOnly) {
			if (existingOnly && !value) return false;
			value = updated;
			return true;
		},
		async remove() { value = null; },
		lock(_id, operation) {
			const result = pending.then(operation);
			pending = result.catch(() => {});
			return result;
		}
	};
}

test('only the two business roles are accepted from verified identity claims', () => {
	const user = userFromClaims({ sub: 'alice', preferred_username: 'Alice', roles: ['LECTEUR', 'ADMIN', 42] });
	assert.deepEqual(user, { sub: 'alice', name: 'Alice', roles: ['LECTEUR'] });
	assert.equal(hasRole(user, 'REGULATEUR'), false);
	assert.deepEqual(userFromClaims({ sub: 'bob', roles: 'REGULATEUR' }).roles, []);
	assert.throws(() => userFromClaims({ roles: ['REGULATEUR'] }));
});

test('the same server session survives requests without exposing or refreshing tokens', async () => {
	const existing = session();
	const repository = store(existing);
	const refresh = async () => { throw new Error('must not refresh'); };
	assert.equal(await resolveSession('opaque-id', repository, refresh, () => now), existing);
	assert.equal(await resolveSession('opaque-id', repository, refresh, () => now + 1000), existing);
});

test('expired absolute session is deleted even when its access token is valid', async () => {
	const existing = { ...session(), expiresAt: now - 1 };
	const repository = store(existing);
	assert.equal(await resolveSession('id', repository, async () => null, () => now), null);
	assert.equal(await repository.read('id'), null);
});

test('refresh rotates tokens and roles without extending the absolute session lifetime', async () => {
	const existing = { ...session(), accessTokenExpiresAt: now + 1000 };
	const repository = store(existing);
	const updated = await resolveSession('id', repository, async (token) => {
		assert.equal(token, 'refresh-token');
		return { accessToken: 'new-access', refreshToken: 'new-refresh', expiresIn: 300,
			user: { ...existing.user, roles: ['LECTEUR'] } };
	}, () => now);
	assert.equal(updated?.accessToken, 'new-access');
	assert.equal(updated?.refreshToken, 'new-refresh');
	assert.deepEqual(updated?.user.roles, ['LECTEUR']);
	assert.equal(updated?.expiresAt, existing.expiresAt);
});

test('a rejected refresh invalidates the session', async () => {
	const repository = store({ ...session(), accessTokenExpiresAt: now });
	assert.equal(await resolveSession('id', repository, async () => null, () => now), null);
	assert.equal(await repository.read('id'), null);
});

test('a temporary identity provider failure fails closed without deleting the session', async () => {
	const repository = store({ ...session(), accessTokenExpiresAt: now });
	await assert.rejects(resolveSession('id', repository, async () => { throw new Error('unavailable'); }, () => now));
	assert.notEqual(await repository.read('id'), null);
});

test('a concurrent logout cannot be undone by an in-flight refresh', async () => {
	const repository = store({ ...session(), accessTokenExpiresAt: now });
	const resolved = await resolveSession('id', repository, async () => {
		await repository.remove('id');
		return { accessToken: 'new', refreshToken: 'new', expiresIn: 300 };
	}, () => now);
	assert.equal(resolved, null);
	assert.equal(await repository.read('id'), null);
});

test('concurrent requests refresh once under the shared session lock', async () => {
	const repository = store({ ...session(), accessTokenExpiresAt: now });
	let calls = 0;
	const refresh = async () => {
		calls++;
		return { accessToken: 'new', refreshToken: 'new', expiresIn: 300 };
	};
	const results = await Promise.all(Array.from({ length: 8 }, () => resolveSession('id', repository, refresh, () => now)));
	assert.equal(calls, 1);
	assert(results.every((result) => result?.accessToken === 'new'));
});
