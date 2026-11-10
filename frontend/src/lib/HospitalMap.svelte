<script lang="ts">
	import { onMount } from 'svelte';
	import type { Map as MapLibreMap } from 'maplibre-gl';
	import type { Hospital } from '#lib/hospital.js';
	import 'maplibre-gl/dist/maplibre-gl.css';

	let { hospitals }: { hospitals: Hospital[] } = $props();
	let container: HTMLDivElement;
	let loading = $state(true);
	let error = $state('');

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
						'Map.Title': 'Carte des hôpitaux fictifs du Grand Londres'
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
<p class="status" role="status">
	{#if error}{error}
	{:else if loading}Chargement de la carte…
	{:else}{hospitals.length} hôpitaux fictifs · Cliquez sur une icône H pour afficher son nom.{/if}
</p>
<noscript><p>Activez JavaScript pour afficher la carte. Le formulaire de réservation reste disponible.</p></noscript>

<style>
	.map { height: 400px; border: 1px solid #d5dfe5; border-radius: 8px; background: #edf2f4; }
	.status { margin: 12px 0 0; color: #52616c; font-size: 0.9rem; }
	.map :global(.medhead-hospital-marker) { width: 36px; height: 36px; padding: 0; border: 3px solid white; border-radius: 50%; background: #17645f; color: white; font: 700 18px system-ui, sans-serif; box-shadow: 0 2px 6px #192d3b55; cursor: pointer; }
	.map :global(.medhead-hospital-marker:focus-visible) { outline: 3px solid #317db4; outline-offset: 3px; }
	.map :global(.maplibregl-popup-content) { color: #192d3b; font: 14px/1.5 system-ui, sans-serif; }
	.map :global(.maplibregl-popup-content p) { margin: 4px 0 0; }
	@media (max-width: 480px) { .map { height: 320px; } }
</style>
