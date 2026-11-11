import { createHash, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { setTimeout } from 'node:timers/promises';
import { createClient } from 'redis';
import type { Session, SessionStore } from './session.js';

const env = process.env;

export const randomId = () => randomBytes(32).toString('base64url');
export const validId = (id: string | undefined): id is string => Boolean(id && /^[A-Za-z0-9_-]{43}$/.test(id));
const key = (kind: string, id: string) => `medhead:auth:${kind}:${createHash('sha256').update(id).digest('hex')}`;

async function connect() {
	const caPath = env.AUTH_REDIS_CA_CERT;
	const client = createClient({
		url: env.AUTH_REDIS_URL ?? 'rediss://localhost:6379/1',
		password: env.REDIS_PASSWORD,
		disableOfflineQueue: true,
		commandOptions: { timeout: 3000 },
		socket: {
			tls: true,
			...(caPath ? { ca: await readFile(caPath) } : {}),
			connectTimeout: 2000,
			reconnectStrategy: false
		}
	});
	client.on('error', () => console.error('Le stockage des sessions est indisponible.'));
	await client.connect();
	return client;
}

let connection: ReturnType<typeof connect> | undefined;
async function redis() {
	if (connection && !(await connection).isReady) connection = undefined;
	connection ??= connect().catch((error) => { connection = undefined; throw error; });
	return connection;
}

export const sessionStore: SessionStore = {
	async read(id) {
		if (!validId(id)) return null;
		const value = await (await redis()).get(key('session', id));
		return value ? JSON.parse(value) as Session : null;
	},
	async write(id, session, existingOnly) {
		const seconds = Math.floor((session.expiresAt - Date.now()) / 1000);
		if (seconds <= 0) return false;
		return (await (await redis()).set(key('session', id), JSON.stringify(session), {
			EX: seconds, ...(existingOnly ? { XX: true } : { NX: true })
		})) === 'OK';
	},
	async remove(id) {
		if (validId(id)) await (await redis()).del(key('session', id));
	},
	async lock(id, operation) {
		const client = await redis();
		const lockKey = key('refresh-lock', id);
		const owner = randomId();
		for (let attempt = 0; attempt < 50; attempt++) {
			if (await client.set(lockKey, owner, { NX: true, PX: 15_000 })) {
				try { return await operation(); }
				finally {
					await client.eval("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", {
						keys: [lockKey], arguments: [owner]
					});
				}
			}
			await setTimeout(100);
		}
		throw new Error('Session refresh is busy');
	}
};

export type LoginTransaction = { state: string; nonce: string; verifier: string };
export async function saveLogin(id: string, transaction: LoginTransaction) {
	await (await redis()).set(key('login', id), JSON.stringify(transaction), { EX: 600 });
}
export async function consumeLogin(id: string | undefined): Promise<LoginTransaction | null> {
	if (!validId(id)) return null;
	const value = await (await redis()).getDel(key('login', id));
	return value ? JSON.parse(value) as LoginTransaction : null;
}
