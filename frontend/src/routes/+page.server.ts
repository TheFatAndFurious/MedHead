import { fail } from '@sveltejs/kit';
import { demoLocations } from '#lib/demo-locations.js';
import type { Hospital } from '#lib/hospital.js';
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

export const load = (async ({ fetch }) => {
	const hospitalCatalogue = fetch(`${apiUrl}/api/v1/hospitals`, {
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
			signal: AbortSignal.timeout(5000)
		});
		if (!response.ok) throw new Error('Specialty catalogue unavailable');
		const specialties: Specialty[] = await response.json();
		return {
			...(await hospitalCatalogue),
			specialties,
			loadError: specialties.length ? null : 'Aucune spécialité n’est disponible pour le moment.'
		};
	} catch {
		return {
			...(await hospitalCatalogue),
			specialties: [] as Specialty[],
			loadError: 'Impossible de charger les spécialités. Vérifiez que le service est démarré.'
		};
	}
}) satisfies PageServerLoad;

export const actions = {
	default: async ({ request, fetch }) => {
		const fields = await request.formData();
		const locationId = fields.get('locationId');
		const specialtyCode = fields.get('specialtyCode');
		const location = demoLocations.find((point) => point.id === locationId);
		const result = {
			locationId: typeof locationId === 'string' ? locationId : '',
			specialtyCode: typeof specialtyCode === 'string' ? specialtyCode : '',
			reservation: null as Reservation | null,
			error: null as string | null,
			uncertain: false
		};

		if (!location || typeof specialtyCode !== 'string' || !/^[A-Z0-9_]{1,100}$/.test(specialtyCode)) {
			return fail(400, { ...result, error: 'Sélectionnez un lieu et une spécialité valides.' });
		}

		try {
			const response = await fetch(`${apiUrl}/api/v1/hospitals/reservations`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					latitude: location.latitude,
					longitude: location.longitude,
					specialtyCode
				}),
				signal: AbortSignal.timeout(10000)
			});
			if (response.status === 404) {
				return fail(404, { ...result, error: 'Aucun hôpital ne dispose d’un lit pour cette spécialité.' });
			}
			if (response.status === 400) {
				return fail(400, { ...result, error: 'La demande a été refusée. Vérifiez le lieu et la spécialité.' });
			}
			if (!response.ok) throw new Error('Reservation confirmation unavailable');
			return { ...result, reservation: (await response.json()) as Reservation };
		} catch {
			return fail(503, {
				...result,
				uncertain: true,
				error: 'La confirmation n’a pas été reçue. Un lit peut avoir été réservé. Vérifiez l’état de la demande avant un nouvel envoi.'
			});
		}
	}
} satisfies Actions;
