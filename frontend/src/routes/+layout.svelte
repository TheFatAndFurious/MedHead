<script lang="ts">
	import favicon from '#lib/assets/favicon.svg';
	import type { LayoutProps } from './$types';

	let { children, data }: LayoutProps = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<nav class="account" aria-label="Compte utilisateur">
	{#if data.user}
		<span><strong>{data.user.name}</strong> · {data.user.roles.includes('REGULATEUR') ? 'Régulation' : 'Lecture seule'}</span>
		<form method="POST" action="/auth/logout"><button type="submit">Se déconnecter</button></form>
	{:else}
		<span>Accès professionnel</span>
		<a href="/auth/login" data-sveltekit-reload>Se connecter</a>
	{/if}
</nav>
{@render children()}

<style>
	.account { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; max-width: 740px; margin: 0 auto; padding: 16px 20px 0; font: 0.9rem/1.5 system-ui, sans-serif; color: #52616c; }
	a, button { color: #17645f; font: inherit; font-weight: 600; }
	button { padding: 6px 10px; border: 1px solid #17645f; border-radius: 6px; background: white; cursor: pointer; }
	a:focus-visible, button:focus-visible { outline: 3px solid #317db4; outline-offset: 3px; }
</style>
