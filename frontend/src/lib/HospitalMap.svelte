<script lang="ts">
	import { onMount } from 'svelte';
	import type { Map as MapLibreMap } from 'maplibre-gl';
	import { demoLocations } from '#lib/demo-locations.js';
	import type { Hospital } from '#lib/hospital.js';
	import 'maplibre-gl/dist/maplibre-gl.css';

	let {
		hospitals,
		selectedLocationId = $bindable(''),
		selectionDisabled = false
	}: {
		hospitals: Hospital[];
		selectedLocationId?: string;
		selectionDisabled?: boolean;
	} = $props();
	let container: HTMLDivElement;
	let loading = $state(true);
	let error = $state('');
	let locationMarkers = $state<{ id: string; name: string; element: HTMLButtonElement }[]>([]);
	const selectedLocation = $derived(demoLocations.find((point) => point.id === selectedLocationId));

	$effect(() => {
		for (const { id, name, element } of locationMarkers) {
			const selected = id === selectedLocationId;
			const label = `${name} — ${selected ? 'Localisation d’urgence sélectionnée' : 'Choisir ce lieu d’urgence'}`;
			element.classList.toggle('is-selected', selected);
			element.setAttribute('aria-pressed', String(selected));
			element.setAttribute('aria-label', label);
			element.title = label;
			element.disabled = selectionDisabled;
		}
	});

	onMount(() => {
		let disposed = false;
		let map: MapLibreMap | undefined;

		async function initialize() {
			try {
				const [maplibre, { default: workerUrl }] = await Promise.all([
					import('maplibre-gl'),
					import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url')
				]);
				if (disposed) return;
				maplibre.setWorkerUrl(workerUrl);
				map = new maplibre.Map({
					container,
					style: 'https://tiles.openfreemap.org/styles/liberty',
					center: [-0.1276, 51.5072],
					zoom: 10,
					attributionControl: { compact: false },
					locale: {
						'NavigationControl.ZoomIn': 'Zoomer',
						'NavigationControl.ZoomOut': 'Dézoomer',
						'Popup.Close': 'Fermer',
						'Map.Title': 'Carte des hôpitaux et lieux d’urgence fictifs du Grand Londres'
					}
				});
				map.addControl(new maplibre.NavigationControl({ showCompass: false }), 'top-right');
				map.on('load', () => { loading = false; });
				map.on('error', () => {
					loading = false;
					error = 'La carte ne peut pas être chargée complètement. Vous pouvez utiliser le formulaire de réservation.';
				});

				const bounds = new maplibre.LngLatBounds();
				for (const hospital of hospitals) {
					const icon = document.createElement('button');
					icon.type = 'button';
					icon.className = 'medhead-hospital-marker';
					icon.textContent = 'H';
					icon.setAttribute('aria-label', `${hospital.name} — Hôpital fictif`);
					icon.title = hospital.name;

					const content = document.createElement('div');
					const name = document.createElement('strong');
					name.textContent = hospital.name;
					const description = document.createElement('p');
					description.textContent = 'Hôpital fictif';
					content.append(name, description);

					new maplibre.Marker({ element: icon })
						.setLngLat([hospital.longitude, hospital.latitude])
						.setPopup(new maplibre.Popup({ offset: 22 }).setDOMContent(content))
						.addTo(map);
					bounds.extend([hospital.longitude, hospital.latitude]);
				}

				locationMarkers = demoLocations.map((point) => {
					const icon = document.createElement('button');
					icon.type = 'button';
					icon.className = 'medhead-location-marker';
					icon.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 40" aria-hidden="true" focusable="false">
						<path d="M16 1.5C8 1.5 1.5 7.8 1.5 15.5C1.5 26 16 38 16 38S30.5 26 30.5 15.5C30.5 7.8 24 1.5 16 1.5Z" fill="currentColor" stroke="white" stroke-width="2"/>
						<circle class="location-dot" cx="16" cy="15.5" r="4" fill="white"/>
						<path class="location-cross" d="M11 15.5h10M16 10.5v10" fill="none" stroke="white" stroke-width="3" stroke-linecap="round"/>
					</svg>`;
					icon.addEventListener('click', () => {
						if (!selectionDisabled) selectedLocationId = point.id;
					});

					new maplibre.Marker({ element: icon, anchor: 'bottom' })
						.setLngLat([point.longitude, point.latitude])
						.addTo(map!);
					bounds.extend([point.longitude, point.latitude]);
					return { id: point.id, name: point.name, element: icon };
				});
				map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 0 });
			} catch {
				if (disposed) return;
				loading = false;
				error = 'La carte est indisponible. Vous pouvez utiliser le formulaire de réservation.';
			}
		}

		void initialize();
		return () => {
			disposed = true;
			map?.remove();
		};
	});
</script>

<div class="map" bind:this={container}></div>
<ul class="legend" aria-label="Légende de la carte">
	<li><span class="legend-hospital" aria-hidden="true">H</span> Hôpital</li>
	<li><span class="legend-location" aria-hidden="true">●</span> Lieu d’urgence</li>
	<li><span class="legend-location legend-selected" aria-hidden="true">+</span> Lieu sélectionné</li>
</ul>
<p class="status" role="status">
	{#if error}{error}
	{:else if loading}Chargement de la carte…
	{:else}
		{hospitals.length} hôpitaux fictifs · {demoLocations.length} lieux d’urgence.
		{#if selectedLocation}Lieu sélectionné : {selectedLocation.name}.
		{:else}Choisissez un lieu sur la carte ou dans le formulaire.{/if}
	{/if}
</p>
<noscript><p>Activez JavaScript pour afficher la carte. Le formulaire de réservation reste disponible.</p></noscript>

<style>
	.map { height: 400px; border: 1px solid #d5dfe5; border-radius: 8px; background: #edf2f4; }
	.legend { display: flex; flex-wrap: wrap; gap: 8px 16px; margin: 12px 0 0; padding: 0; list-style: none; color: #52616c; font-size: 0.85rem; }
	.legend li { display: flex; align-items: center; gap: 6px; }
	.legend-hospital, .legend-location { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: #17645f; color: white; font-weight: 700; }
	.legend-location { background: #737d87; font-size: 0.65rem; }
	.legend-selected { background: #b13c2f; font-size: 1.2rem; }
	.status { margin: 12px 0 0; color: #52616c; font-size: 0.9rem; }
	.map :global(.medhead-hospital-marker) { width: 36px; height: 36px; padding: 0; border: 3px solid white; border-radius: 50%; background: #17645f; color: white; font: 700 18px system-ui, sans-serif; box-shadow: 0 2px 6px #192d3b55; cursor: pointer; }
	.map :global(.medhead-location-marker) { display: flex; align-items: flex-end; justify-content: center; width: 40px; height: 48px; padding: 0; border: 0; border-radius: 8px; background: transparent; color: #737d87; cursor: pointer; }
	.map :global(.medhead-location-marker svg) { width: 32px; height: 40px; filter: drop-shadow(0 2px 3px #192d3b55); }
	.map :global(.medhead-location-marker .location-cross) { display: none; }
	.map :global(.medhead-location-marker.is-selected) { color: #b13c2f; z-index: 1; }
	.map :global(.medhead-location-marker.is-selected svg) { width: 36px; height: 45px; }
	.map :global(.medhead-location-marker.is-selected .location-dot) { display: none; }
	.map :global(.medhead-location-marker.is-selected .location-cross) { display: block; }
	.map :global(.medhead-location-marker:disabled) { cursor: default; }
	.map :global(.medhead-hospital-marker:focus-visible), .map :global(.medhead-location-marker:focus-visible) { outline: 3px solid #317db4; outline-offset: 3px; }
	.map :global(.maplibregl-popup-content) { color: #192d3b; font: 14px/1.5 system-ui, sans-serif; }
	.map :global(.maplibregl-popup-content p) { margin: 4px 0 0; }
	@media (max-width: 480px) { .map { height: 320px; } }
</style>
