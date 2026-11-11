import { randomUUID } from 'node:crypto';
import { error, fail } from '@sveltejs/kit';
import { demoLocations } from '#lib/demo-locations.js';
import type { Hospital } from '#lib/hospital.js';
import { hasRole } from '#lib/server/session.js';
import type { Actions, PageServerLoad } from './$types';

type Specialty = { code: string; name: string };
type Reservation = {
	reservationId: string;
	reservedAt: string;
	hospitalName: string;
	latitude: number;
	longitude: number;
	availableBeds: number;
	distanceMeters: number;
	specialtyCode: string;
};

const apiUrl = process.env.MEDHEAD_API_URL ?? 'http://localhost:8080';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const load = (async ({ fetch, locals, url }) => {
	// Idempotency-Key of the next reservation request, sent back by the form.
	const reservationKey = randomUUID();
	const canReserve = hasRole(locals.user, 'REGULATEUR');
	if (!locals.user || !locals.accessToken) return {
		hospitals: [] as Hospital[], specialties: [] as Specialty[],
		mapError: null, loadError: null, canReserve: false, reservationKey,
		logoutLocal: url.searchParams.get('logout') === 'local'
	};
	if (!canReserve && !hasRole(locals.user, 'LECTEUR')) error(403, 'Votre compte ne possède aucun rôle MedHead.');
	const headers = { Authorization: `Bearer ${locals.accessToken}` };
	const hospitalCatalogue = fetch(`${apiUrl}/api/v1/hospitals`, {
		headers,
		signal: AbortSignal.timeout(5000)
	}).then(async (response) => {
		if (!response.ok) throw new Error('Hospital catalogue unavailable');
		return { hospitals: (await response.json()) as Hospital[], mapError: null };
	}).catch(() => ({
		hospitals: [] as Hospital[],
		mapError: 'Impossible de charger les hôpitaux. Vous pouvez utiliser le formulaire de réservation.'
	}));

	try {
		const response = await fetch(`${apiUrl}/api/v1/specialties`, {
			headers,
			signal: AbortSignal.timeout(5000)
		});
		if (!response.ok) throw new Error('Specialty catalogue unavailable');
		const specialties: Specialty[] = await response.json();
		return {
			canReserve,
			reservationKey,
			logoutLocal: false,
			...(await hospitalCatalogue),
			specialties,
			loadError: specialties.length ? null : 'Aucune spécialité n’est disponible pour le moment.'
		};
	} catch {
		return {
			canReserve,
			reservationKey,
			logoutLocal: false,
			...(await hospitalCatalogue),
			specialties: [] as Specialty[],
			loadError: 'Impossible de charger les spécialités. Vérifiez que le service est démarré.'
		};
	}
}) satisfies PageServerLoad;

export const actions = {
	default: async ({ request, fetch, locals }) => {
		const fields = await request.formData();
		const locationId = fields.get('locationId');
		const specialtyCode = fields.get('specialtyCode');
		const submittedKey = fields.get('idempotencyKey');
		const idempotencyKey = typeof submittedKey === 'string' && uuid.test(submittedKey) ? submittedKey : null;
		const location = demoLocations.find((point) => point.id === locationId);
		const result = {
			locationId: typeof locationId === 'string' ? locationId : '',
			specialtyCode: typeof specialtyCode === 'string' ? specialtyCode : '',
			reservation: null as Reservation | null,
			replayed: false,
			error: null as string | null,
			uncertain: false,
			// Key for the next submission. A definite answer starts a new request with a new
			// key; only an uncertain result keeps the same key, so resending it is safe.
			idempotencyKey: randomUUID()
		};
		if (!locals.user || !locals.accessToken) {
			return fail(401, { ...result, error: 'Votre session a expiré. Reconnectez-vous avant de réserver.' });
		}
		if (!hasRole(locals.user, 'REGULATEUR')) {
			return fail(403, { ...result, error: 'Le rôle Régulateur est nécessaire pour réserver un lit.' });
		}

		if (!location || typeof specialtyCode !== 'string' || !/^[A-Z0-9_]{1,100}$/.test(specialtyCode)) {
			return fail(400, { ...result, error: 'Sélectionnez un lieu et une spécialité valides.' });
		}
		if (!idempotencyKey) {
			return fail(400, { ...result, error: 'La demande est incomplète. Rechargez la page avant de réserver.' });
		}

		try {
			const response = await fetch(`${apiUrl}/api/v1/hospitals/reservations`, {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					Authorization: `Bearer ${locals.accessToken}`,
					'Idempotency-Key': idempotencyKey
				},
				body: JSON.stringify({
					latitude: location.latitude,
					longitude: location.longitude,
					specialtyCode
				}),
				signal: AbortSignal.timeout(10000)
			});
			if (response.status === 401 || response.status === 403) {
				return fail(response.status, { ...result, error: response.status === 401
					? 'Votre accès a expiré. Reconnectez-vous avant de réserver.'
					: 'Votre compte n’est pas autorisé à réserver un lit.' });
			}
			if (response.status === 404) {
				return fail(404, { ...result, error: 'Aucun hôpital ne dispose d’un lit pour cette spécialité.' });
			}
			if (response.status === 400) {
				return fail(400, { ...result, error: 'La demande a été refusée. Vérifiez le lieu et la spécialité.' });
			}
			if (response.status === 422) {
				return fail(422, { ...result, error: 'Cette demande a déjà servi pour une autre réservation. Rechargez la page avant un nouvel envoi.' });
			}
			if (!response.ok) throw new Error('Reservation confirmation unavailable');
			return {
				...result,
				reservation: (await response.json()) as Reservation,
				replayed: response.headers.get('Idempotent-Replayed') === 'true'
			};
		} catch {
			return fail(503, {
				...result,
				idempotencyKey,
				uncertain: true,
				error: 'La confirmation n’a pas été reçue. Un lit a peut-être déjà été réservé. Renvoyez la même demande : si la réservation existe, elle sera affichée sans réserver de second lit.'
			});
		}
	}
} satisfies Actions;
