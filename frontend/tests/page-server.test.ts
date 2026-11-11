import { test } from 'node:test';
import assert from 'node:assert/strict';
import { actions, load } from '../src/routes/+page.server.ts';
import { demoLocations } from '../src/lib/demo-locations.ts';

const KEY = '6f1c2b8e-0d3a-4c55-9e7a-1b2c3d4e5f60';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const identity = (role: string) => ({ sub: 'alice', name: 'Alice', roles: [role] });
const fields = (locationId = 'westminster', idempotencyKey = KEY) => new Request('https://medhead.example.test/', {
	method: 'POST', body: new URLSearchParams({ locationId, specialtyCode: 'CARDIOLOGY', idempotencyKey })
});
const forbiddenFetch = async () => { throw new Error('No API call should occur'); };
const regulator = { user: identity('REGULATEUR'), accessToken: 'server-only-access' };

test('anonymous page does not fetch or expose protected data', async () => {
	const data = await load({ locals: { user: null, accessToken: null }, fetch: forbiddenFetch,
		url: new URL('https://medhead.example.test/') } as never);
	assert.deepEqual(data.hospitals, []);
	assert.deepEqual(data.specialties, []);
	assert.equal(data.canReserve, false);
	assert.equal(data.logoutLocal, false);
});

test('reader loads both catalogues with a server-only Bearer and cannot reserve', async () => {
	let calls = 0;
	const data = await load({
		locals: { user: identity('LECTEUR'), accessToken: 'server-only-access' },
		fetch: async (_url: string, options: RequestInit) => {
			calls++;
			assert.equal(new Headers(options.headers).get('Authorization'), 'Bearer server-only-access');
			return Response.json([]);
		}
	} as never);
	assert.equal(calls, 2);
	assert.equal(data.canReserve, false);
	assert.equal(JSON.stringify(data).includes('server-only-access'), false);
	const result = await actions.default({ request: fields(), fetch: forbiddenFetch,
		locals: { user: identity('LECTEUR'), accessToken: 'server-only-access' } } as never);
	assert.equal(result.status, 403);
	assert.equal(result.data.uncertain, false);
});

test('each page load provides a new request key', async () => {
	const context = { locals: regulator, fetch: async () => Response.json([]) } as never;
	const first = await load(context);
	const second = await load(context);
	assert.match(first.reservationKey, UUID);
	assert.notEqual(first.reservationKey, second.reservationKey);
});

test('anonymous and invalid-location submissions never reserve a bed', async () => {
	const anonymous = await actions.default({ request: fields(), fetch: forbiddenFetch,
		locals: { user: null, accessToken: null } } as never);
	assert.equal(anonymous.status, 401);
	const invalid = await actions.default({ request: fields('attacker-supplied-point'), fetch: forbiddenFetch,
		locals: regulator } as never);
	assert.equal(invalid.status, 400);
});

test('a submission without a valid request key never reaches the API', async () => {
	for (const key of ['', 'retry-1']) {
		const result = await actions.default({ request: fields('westminster', key), fetch: forbiddenFetch,
			locals: regulator } as never);
		assert.equal(result.status, 400);
		assert.equal(result.data.uncertain, false);
	}
});

test('regulator submission forwards trusted coordinates, Bearer and request key', async () => {
	let calls = 0;
	const result = await actions.default({ request: fields(), locals: regulator,
		fetch: async (_url: string, options: RequestInit) => {
			calls++;
			const headers = new Headers(options.headers);
			assert.equal(headers.get('Authorization'), 'Bearer server-only-access');
			assert.equal(headers.get('Idempotency-Key'), KEY);
			assert.deepEqual(JSON.parse(options.body as string), {
				latitude: demoLocations[0].latitude, longitude: demoLocations[0].longitude, specialtyCode: 'CARDIOLOGY'
			});
			return Response.json({ reservationId: 'reservation-test' }, { status: 201 });
		}
	} as never);
	assert.equal(calls, 1);
	assert.equal(result.reservation?.reservationId, 'reservation-test');
	assert.equal(result.replayed, false);
	// A confirmed reservation ends the request: the next submission is a new request.
	assert.match(result.idempotencyKey, UUID);
	assert.notEqual(result.idempotencyKey, KEY);
	assert.equal(JSON.stringify(result).includes('server-only-access'), false);
});

test('a repeated request reports the original reservation as already recorded', async () => {
	const result = await actions.default({ request: fields(), locals: regulator,
		fetch: async () => Response.json({ reservationId: 'reservation-test' }, {
			status: 201, headers: { 'Idempotent-Replayed': 'true' }
		})
	} as never);
	assert.equal(result.reservation?.reservationId, 'reservation-test');
	assert.equal(result.replayed, true);
});

test('API refusals are definite, never retried, and start a new request key', async () => {
	for (const status of [401, 403, 404, 422]) {
		let calls = 0;
		const result = await actions.default({ request: fields(), locals: regulator,
			fetch: async () => { calls++; return new Response(null, { status }); }
		} as never);
		assert.equal(calls, 1);
		assert.equal(result.status, status);
		assert.equal(result.data.uncertain, false);
		assert.notEqual(result.data.idempotencyKey, KEY);
	}
});

test('a lost reservation response keeps its request key so it can be resent safely', async () => {
	let calls = 0;
	const result = await actions.default({ request: fields(), locals: regulator,
		fetch: async () => { calls++; throw new Error('timeout'); }
	} as never);
	assert.equal(calls, 1);
	assert.equal(result.status, 503);
	assert.equal(result.data.uncertain, true);
	assert.equal(result.data.idempotencyKey, KEY);
});
