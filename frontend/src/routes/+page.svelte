<script lang="ts">
	import { enhance } from '$app/forms';
	import { untrack } from 'svelte';
	import { demoLocations } from '#lib/demo-locations.js';
	import HospitalMap from '#lib/HospitalMap.svelte';
	import type { PageProps } from './$types';

	let { data, form }: PageProps = $props();
	let locationId = $state(untrack(() => form?.locationId ?? ''));
	let specialtyCode = $state(untrack(() => form?.specialtyCode ?? ''));
	let pending = $state(false);
	let submissionError = $state('');
	const location = $derived(demoLocations.find((point) => point.id === locationId));
</script>

<svelte:head>
	<title>MedHead</title>
	<meta name="description" content="Démonstration du système d’intervention d’urgence MedHead." />
</svelte:head>

<main>
	<header>
		<p class="eyebrow">MedHead · Démonstration</p>
		<h1>Réserver un lit</h1>
		<p>Choisissez le lieu de l’urgence et la spécialité. Le système réserve un lit dans l’hôpital disponible le plus proche.</p>
		<p class="muted">Données fictives · Grand Londres</p>
	</header>

	<section class="panel" aria-labelledby="map-heading">
		<h2 id="map-heading">Hôpitaux de démonstration</h2>
		{#if data.mapError}
			<p class="error" role="status">{data.mapError}</p>
		{:else if data.hospitals.length === 0}
			<p class="muted">Aucun hôpital n’est disponible sur la carte.</p>
		{:else}
			{#key data.hospitals}
				<HospitalMap hospitals={data.hospitals} />
			{/key}
		{/if}
	</section>

	<section class="panel" aria-labelledby="request-heading">
		<h2 id="request-heading">Votre demande</h2>
		{#if data.loadError}
			<p class="error" role="alert">{data.loadError}</p>
			<a href="/" data-sveltekit-reload>Recharger les spécialités</a>
		{/if}
		<form
			method="POST"
			use:enhance={({ cancel }) => {
				if (pending || form?.uncertain || submissionError) {
					cancel();
					return;
				}
				pending = true;
				return async ({ result, update }) => {
					try {
						if (result.type === 'error') {
							submissionError = 'La confirmation n’a pas été reçue. Un lit peut avoir été réservé. Vérifiez l’état de la demande avant un nouvel envoi.';
						} else {
							await update({ reset: false, refreshAll: false });
						}
					} finally {
						pending = false;
					}
				};
			}}
		>
			<fieldset disabled={pending || Boolean(data.loadError)}>
				<label for="location">Localisation de l’urgence</label>
				<select id="location" name="locationId" bind:value={locationId} required aria-describedby="coordinates">
					<option value="" disabled>Choisir un lieu de démonstration</option>
					{#each demoLocations as point (point.id)}
						<option value={point.id}>{point.name}</option>
					{/each}
				</select>
				<p id="coordinates" class="muted">
					{#if location}Latitude : {location.latitude} · Longitude : {location.longitude}
					{:else}Les coordonnées du lieu choisi s’afficheront ici.{/if}
				</p>

				<label for="specialty">Spécialité médicale</label>
				<select id="specialty" name="specialtyCode" bind:value={specialtyCode} required>
					<option value="" disabled>Choisir une spécialité</option>
					{#each data.specialties as specialty (specialty.code)}
						<option value={specialty.code}>{specialty.name}</option>
					{/each}
				</select>
				<p class="muted">{data.specialties.length} spécialités dans le référentiel.</p>
				<button type="submit" disabled={pending || !locationId || !specialtyCode || form?.uncertain || Boolean(submissionError)}>
					{pending ? 'Réservation en cours…' : 'Réserver un lit'}
				</button>
			</fieldset>
			<p class="notice">Chaque envoi réussi réserve un lit dans les données de démonstration.</p>
		</form>
	</section>

	<div aria-live="polite" aria-atomic="true">
		{#if pending}
			<p>Recherche d’un hôpital et réservation en cours…</p>
		{:else if submissionError || form?.error}
			<p class="panel error" role="alert">{submissionError || form?.error}</p>
		{:else if form?.reservation}
			<section class="panel result" aria-labelledby="result-heading">
				<p class="eyebrow">Dernière réservation confirmée</p>
				<h2 id="result-heading">{form.reservation.hospitalName}</h2>
				<dl>
					<dt>Départ</dt><dd>{demoLocations.find((point) => point.id === form.locationId)?.name}</dd>
					<dt>Spécialité</dt><dd>{data.specialties.find((specialty) => specialty.code === form.reservation?.specialtyCode)?.name ?? form.reservation.specialtyCode}</dd>
					<dt>Distance à vol d’oiseau</dt><dd>{(form.reservation.distanceMeters / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 2 })} km</dd>
					<dt>Localisation de l’hôpital</dt><dd>{form.reservation.latitude}, {form.reservation.longitude}</dd>
					<dt>Lits restants après réservation</dt><dd>{form.reservation.availableBeds}</dd>
					<dt>Référence</dt><dd>{form.reservation.reservationId}</dd>
					<dt>Date (UTC)</dt><dd>{new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'UTC' }).format(new Date(form.reservation.reservedAt))}</dd>
				</dl>
			</section>
		{/if}
	</div>
</main>

<style>
	:global(body) { margin: 0; background: #f3f6f8; color: #192d3b; font-family: system-ui, sans-serif; line-height: 1.5; }
	:global(*) { box-sizing: border-box; }
	main { max-width: 740px; margin: 0 auto; padding: 40px 20px; }
	header { margin-bottom: 28px; }
	h1 { font-size: 2rem; margin: 8px 0; }
	h2 { font-size: 1.2rem; margin: 0 0 20px; }
	.eyebrow { color: #17645f; font-size: 0.85rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }
	.panel { padding: 24px; margin-bottom: 24px; border: 1px solid #d5dfe5; border-radius: 12px; background: white; }
	fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
	label { display: block; margin-bottom: 8px; font-weight: 600; }
	select, button { width: 100%; min-height: 46px; border-radius: 6px; font: inherit; }
	select { padding: 10px; background: white; color: inherit; border: 1px solid #8396a4; }
	button { margin-top: 8px; border: 0; background: #17645f; color: white; font-weight: 600; cursor: pointer; }
	button:disabled { background: #dce4e8; color: #52616c; cursor: default; }
	select:focus-visible, button:focus-visible, a:focus-visible { outline: 3px solid #317db4; outline-offset: 3px; }
	.muted, .notice { color: #52616c; font-size: 0.9rem; }
	.muted { margin: 8px 0 24px; }
	.notice { margin-bottom: 0; }
	.error { color: #9c2828; }
	a { color: #17645f; }
	.result { border-top: 4px solid #17645f; }
	dl { margin: 0; }
	dt { font-size: 0.85rem; color: #52616c; margin-top: 12px; }
	dd { margin: 2px 0 0; overflow-wrap: anywhere; }
	@media (max-width: 480px) { main { padding: 24px 16px; } .panel { padding: 20px; } }
</style>
