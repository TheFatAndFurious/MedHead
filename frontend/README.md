# MedHead — Front-end

Base SvelteKit avec TypeScript et l’adaptateur Node. Le choix de SvelteKit a
été autorisé pour ce POC, en dérogation à la liste du document des exigences.
La page permet de choisir un lieu de démonstration et une spécialité, puis de
réserver un lit avec l’API Spring. Une carte MapLibre avec un fond OpenFreeMap
affiche les hôpitaux fictifs du Grand Londres.
L’accès passe par Keycloak. Une session serveur conserve les jetons dans Redis ;
le navigateur reçoit un cookie contenant uniquement un identifiant aléatoire.
Les comptes et les détails de configuration sont décrits dans
[`docs/authentification.md`](../docs/authentification.md).

## Démarrage avec Docker

Depuis la racine du dépôt :

```bash
docker compose up --build
```

Ouvrir <http://localhost:5173>. Node et npm ne sont pas nécessaires sur la machine
hôte. Si l’API est déjà démarrée, on peut lancer uniquement le front-end avec
`docker compose up --build frontend`.

Ce service exécute l’application compilée avec l’adaptateur Node (cible
`runtime` du Dockerfile, image `medhead-frontend:runtime`). Le code source n’est
pas monté dans le conteneur : relancer la commande avec `--build` après une
modification. Les fichiers de l’application appartiennent à `root` et le
processus tourne avec l’utilisateur non privilégié `node`, qui peut les lire
sans pouvoir les modifier.

L’origine publique (`MEDHEAD_PUBLIC_ORIGIN`) est aussi compilée dans
l’application (`kit.paths.origin` dans `vite.config.ts`), via un argument de
construction transmis par Compose. SvelteKit s’en sert pour construire les URL
des requêtes et pour sa vérification CSRF des formulaires. Sans elle,
l’adaptateur Node déduit l’origine des en-têtes en supposant HTTPS, et les
formulaires envoyés en HTTP local sont refusés (`403`). Après un changement de
`MEDHEAD_PUBLIC_ORIGIN` ou de `FRONTEND_PORT`, relancer avec `--build`.

Le serveur SvelteKit utilise `MEDHEAD_API_URL=http://api:8080` dans Docker.
Les appels à Spring restent côté serveur. Le navigateur charge le fond de carte
directement depuis OpenFreeMap. Pour un lancement hors Docker, la valeur par défaut est
`http://localhost:8080`.

## Parcours de démonstration

1. Choisir un des huit secteurs du Grand Londres, dans le formulaire ou sur la
   carte. Ses coordonnées s’affichent et son repère devient rouge avec une croix.
2. Choisir une spécialité parmi celles chargées depuis l’API.
3. Cliquer sur « Réserver un lit ».
4. Consulter l’hôpital retenu et la référence de réservation.

Chaque réussite consomme un lit. Les choix restent sélectionnés après l’envoi.
Se connecter avec le compte `regulateur` pour ce parcours. Le compte `lecteur`
consulte la carte et les spécialités avec le formulaire de réservation désactivé.
Une spécialité sans lit disponible affiche un message sans créer de réservation.
La référence du résultat est conservée sur la page, sans historique persistant.

### Demande sans confirmation

Chaque demande porte une clé d’idempotence (UUID) dans un champ caché du
formulaire. Le serveur SvelteKit la génère au chargement de la page et la
transmet à l’API dans l’en-tête `Idempotency-Key`.

- Après une réponse définitive (réservation, refus, absence de lit), la demande
  suivante reçoit une nouvelle clé.
- Si la confirmation se perd (délai dépassé, API ou réseau indisponible), un
  message indique le résultat incertain. Le lieu et la spécialité sont
  verrouillés et le bouton devient « Renvoyer la même demande » : il renvoie la
  même clé. Si le premier envoi avait réservé un lit, l’API renvoie cette
  réservation, signalée comme « déjà enregistrée », sans consommer de second
  lit. Sinon, la réservation est créée.
- Le lien « Abandonner cette demande » recharge la page avec une nouvelle clé ;
  un lit éventuellement réservé par l’envoi perdu le reste.

Aucune réservation n’est relancée automatiquement : le renvoi reste une action
de l’utilisateur. Ce fonctionnement marche aussi sans JavaScript, la clé à
réutiliser étant renvoyée par l’action du formulaire.

Les points de départ fictifs sont répartis à distance des hôpitaux pour distinguer
les repères sur la carte et rendre les distances de réservation plus visibles.
Ils sont définis dans `src/lib/demo-locations.ts` et validés côté serveur.
Les champs du navigateur ne permettent pas de choisir d’autres
coordonnées. Le référentiel de spécialités est récupéré à chaque chargement
de la page, sans nouvelle lecture après une soumission améliorée par JavaScript.

Pour vérifier le parcours avec les données initiales, choisir le secteur
Westminster et « Cardiologie ». Pour vérifier l’absence de résultat, choisir
« Allergie », non représentée dans les hôpitaux de démonstration.

La remise à l’état initial des hôpitaux est décrite dans le README principal.

## Carte des hôpitaux et lieux d’urgence

Le serveur SvelteKit charge les hôpitaux depuis `GET /api/v1/hospitals`, en
parallèle du référentiel de spécialités. La carte affiche une icône H par
hôpital, y compris ceux sans lit disponible. Un clic affiche le nom et la
mention « Hôpital fictif ». Les huit lieux d’urgence pré-enregistrés sont affichés
avec des repères gris. Le lieu sélectionné utilise un repère rouge plus grand
avec une croix blanche. Un clic sur un lieu le sélectionne aussi dans le formulaire,
et un changement dans le formulaire met la carte à jour. La sélection sur la carte
est désactivée pendant une réservation, comme dans le formulaire. Une légende
explique les symboles. Le cadrage initial inclut tous les hôpitaux et lieux d’urgence.

`src/lib/HospitalMap.svelte` initialise MapLibre uniquement dans le navigateur
et libère la carte quand le composant est retiré. Le worker MapLibre est compilé
par Vite. Le fond utilise le style Liberty d’OpenFreeMap. Les crédits restent
visibles sur la carte.

Aucun compte ni clé d’accès n’est nécessaire. Une connexion Internet est
nécessaire pour le fond de carte. Si les hôpitaux ou la carte ne chargent pas,
un message s’affiche et le formulaire reste utilisable si les spécialités sont
disponibles. La carte nécessite JavaScript et WebGL.

## Mode développement et rechargement automatique

Le fichier `docker-compose.dev.yaml`, à la racine du dépôt, remplace l’application
compilée par le serveur de développement Vite (cible `dev`, image
`medhead-frontend:dev`, distincte de l’image de démonstration) :

```bash
docker compose -f docker-compose.yaml -f docker-compose.dev.yaml up --build --watch
```

Compose Watch (Docker Compose 2.22 ou plus) surveille le dossier `frontend` :

- une modification dans `src` ou `static` est copiée dans le conteneur et
  appliquée automatiquement dans le navigateur ;
- une modification de `package.json`, `package-lock.json`, `vite.config.ts` ou
  `tsconfig.json` reconstruit l’image et relance le conteneur. Les dépendances
  installées correspondent donc toujours à `package-lock.json`.

Le code n’est pas monté depuis la machine hôte : les fichiers sont copiés dans
le conteneur, ce qui évite qu’une ancienne image soit exécutée avec un code
plus récent. Pour revenir à l’application compilée, relancer
`docker compose up --build` sans le fichier de développement.

Pour changer le port local, définir `FRONTEND_PORT` dans le fichier `.env` à la
racine du dépôt. Le port interne reste `5173`.

Arrêter le front-end :

```bash
docker compose stop frontend
```

## Vérifications

L’image de démonstration ne contient ni les sources, ni les tests, ni les
dépendances de développement. Les vérifications utilisent donc l’image de
développement ; `--build` la reconstruit avec le code et les dépendances actuels.

Vérifier les composants et les types TypeScript :

```bash
docker compose -f docker-compose.yaml -f docker-compose.dev.yaml run --build --rm --no-deps frontend npm run check
```

Vérifier les sessions, les cookies et la protection CSRF :

```bash
docker compose -f docker-compose.yaml -f docker-compose.dev.yaml run --build --rm --no-deps frontend npm test
```

La compilation avec l’adaptateur Node est vérifiée en construisant l’image de
démonstration :

```bash
docker compose build frontend
```

Cette image est un premier pas vers un déploiement : application compilée,
utilisateur non privilégié. Il manque encore notamment HTTPS, des en-têtes de
sécurité (CSP) et une gestion des secrets propre à l’environnement.

## Dépendances

`package-lock.json` fixe les versions installées par `npm ci` dans l’image.
Ne pas versionner `node_modules`, `.svelte-kit` ou `build`.

L’audit initial des dépendances signale quatre alertes de niveau faible liées
à `deepmerge`, utilisé dans la chaîne de compilation. Aucun correctif compatible
n’est proposé par npm au moment de l’installation.
