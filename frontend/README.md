# MedHead — Front-end

Base SvelteKit avec TypeScript et l’adaptateur Node. Le choix de SvelteKit a
été autorisé pour ce POC, en dérogation à la liste du document des exigences.
Le parcours de réservation et la carte seront ajoutés dans les étapes suivantes.

## Développement avec Docker

Depuis la racine du dépôt :

```bash
docker compose up --build frontend
```

Ouvrir <http://localhost:5173>. Le service peut démarrer seul. Il ne dépend pas
encore de l’API. Node et npm ne sont pas nécessaires sur la machine hôte.

Les répertoires `src` et `static` sont partagés avec le conteneur. Les changements
de source sont appliqués automatiquement dans le navigateur. Après une
modification des dépendances ou de la configuration, reconstruire le service
avec la même commande.

Pour changer le port local, définir `FRONTEND_PORT` dans le fichier `.env` à la
racine du dépôt. Le port interne reste `5173`.

Arrêter le front-end :

```bash
docker compose stop frontend
```

## Vérifications

Construire d’abord l’image après toute modification des dépendances ou de la
configuration :

```bash
docker compose build frontend
```

Vérifier les composants et les types TypeScript :

```bash
docker compose run --rm --no-deps frontend npm run check
```

Vérifier la compilation de l’application :

```bash
docker compose run --rm --no-deps frontend npm run build
```

La compilation utilise l’adaptateur Node. Le service Compose lance le serveur
de développement avec rechargement automatique ; il ne constitue pas encore
un déploiement de production.

## Dépendances

`package-lock.json` fixe les versions installées par `npm ci` dans l’image.
Ne pas versionner `node_modules`, `.svelte-kit` ou `build`.

L’audit initial des dépendances signale quatre alertes de niveau faible liées
à `deepmerge`, utilisé dans la chaîne de compilation. Aucun correctif compatible
n’est proposé par npm au moment de l’installation.
