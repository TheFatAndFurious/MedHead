# MedHead — Front-end

Base SvelteKit avec TypeScript et l’adaptateur Node. Le choix de SvelteKit a
été autorisé pour ce POC, en dérogation à la liste du document des exigences.
La page permet de choisir un lieu de démonstration et une spécialité, puis de
réserver un lit avec l’API Spring. Une carte MapLibre avec un fond OpenFreeMap
affiche les hôpitaux fictifs du Grand Londres.

## Développement avec Docker

Depuis la racine du dépôt :

```bash
docker compose up --build
```

Ouvrir <http://localhost:5173>. Node et npm ne sont pas nécessaires sur la machine
hôte. Si l’API est déjà démarrée, on peut lancer uniquement le front-end avec
`docker compose up --build frontend`.

Le serveur SvelteKit utilise `MEDHEAD_API_URL=http://api:8080` dans Docker.
Les appels à Spring restent côté serveur. Le navigateur charge le fond de carte
directement depuis OpenFreeMap. Pour un lancement hors Docker, la valeur par défaut est
`http://localhost:8080`.

## Parcours de démonstration

1. Choisir un des huit secteurs du Grand Londres. Ses coordonnées s’affichent.
2. Choisir une spécialité parmi celles chargées depuis l’API.
3. Cliquer sur « Réserver un lit ».
4. Consulter l’hôpital retenu et la référence de réservation.

Chaque réussite consomme un lit. Les choix restent sélectionnés après l’envoi.
Une spécialité sans lit disponible affiche un message sans créer de réservation.
Si une confirmation se perd, un message indique le résultat incertain et bloque
un nouvel envoi sur la page. Aucune réservation n’est relancée automatiquement.
La référence du résultat est conservée sur la page, sans historique persistant.

Les points de départ sont définis dans `src/lib/demo-locations.ts` et validés
côté serveur. Les champs du navigateur ne permettent pas de choisir d’autres
coordonnées. Le référentiel de spécialités est récupéré à chaque chargement
de la page, sans nouvelle lecture après une soumission améliorée par JavaScript.

Pour vérifier le parcours avec les données initiales, choisir le secteur
Westminster et « Cardiologie ». Pour vérifier l’absence de résultat, choisir
« Allergie », non représentée dans les hôpitaux de démonstration.

La remise à l’état initial des hôpitaux est décrite dans le README principal.

## Carte des hôpitaux

Le serveur SvelteKit charge les hôpitaux depuis `GET /api/v1/hospitals`, en
parallèle du référentiel de spécialités. La carte affiche une icône H par
hôpital, y compris ceux sans lit disponible. Un clic affiche le nom et la
mention « Hôpital fictif ». Le cadrage initial inclut tous les hôpitaux.

`src/lib/HospitalMap.svelte` initialise MapLibre uniquement dans le navigateur
et libère la carte quand le composant est retiré. Le worker MapLibre est compilé
par Vite. Le fond utilise le style Liberty d’OpenFreeMap. Les crédits restent
visibles sur la carte.

Aucun compte ni clé d’accès n’est nécessaire. Une connexion Internet est
nécessaire pour le fond de carte. Si les hôpitaux ou la carte ne chargent pas,
un message s’affiche et le formulaire reste utilisable si les spécialités sont
disponibles. La carte nécessite JavaScript et WebGL.

## Rechargement automatique

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
