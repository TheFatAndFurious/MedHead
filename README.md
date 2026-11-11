# MedHead

## Démarrage avec Docker

Créer le fichier de configuration locale :

```bash
cp .env.example .env
```

Démarrer PostGIS, Redis, Keycloak, l'API et le front-end :

```bash
docker compose up --build
```

L'API est accessible sur <http://localhost:8080> et son état sur
<http://localhost:8080/actuator/health>. Au premier démarrage, Flyway crée le
schéma, charge les 80 spécialités du référentiel NHS, puis Docker ajoute 12
hôpitaux fictifs répartis dans le Grand Londres.

Le front-end SvelteKit est accessible sur <http://localhost:5173>.
Cliquer sur « Se connecter » et utiliser un des comptes locaux de démonstration :

| Compte | Mot de passe initial | Droits |
| --- | --- | --- |
| `lecteur` | `lecteur_demo` | Carte et référentiel en lecture seule |
| `regulateur` | `regulateur_demo` | Consultation et réservation d’un lit |

La console Keycloak est sur <http://localhost:8180> (administrateur initial
`admin` / `medhead_admin_demo`). Ces valeurs de démonstration se règlent dans
`.env` avant le premier démarrage. Utiliser `localhost` pour le navigateur ;
les URL de retour sont enregistrées avec cette origine. Le détail des sessions,
des rôles et des vérifications est dans [le guide d’authentification](docs/authentification.md).

Le front-end propose une liste de lieux de démonstration dans le Grand Londres et charge
les spécialités depuis l’API. Le bouton « Réserver un lit » envoie la demande
et affiche l’hôpital retenu, la distance et la référence de réservation.
Chaque réussite consomme un lit du jeu de démonstration.
`FRONTEND_PORT` permet de changer le port local.

Pour démarrer uniquement le front-end :

```bash
docker compose up --build frontend
```

L’API doit déjà être démarrée pour charger les spécialités et réserver un lit.
Sinon, la page affiche un message et permet de recharger les spécialités.

Le conteneur exécute l’application compilée avec l’utilisateur non privilégié
`node`, sans montage du code source : relancer `docker compose up --build`
après une modification. Pour développer avec rechargement automatique, ajouter
le fichier `docker-compose.dev.yaml` :

```bash
docker compose -f docker-compose.yaml -f docker-compose.dev.yaml up --build --watch
```

Ce mode lance le serveur de développement Vite. Il copie les modifications de
`frontend/src` et `frontend/static` dans le conteneur et reconstruit l’image
quand les dépendances changent. Le mode développement, la configuration et les
commandes de vérification sont décrits dans le
[guide du front-end](frontend/README.md).

## Hôpitaux et spécialités pour le front-end

Deux routes de lecture fournissent les données de la carte et du formulaire :
elles nécessitent désormais un JWT d’accès valide avec le rôle `LECTEUR` ou
`REGULATEUR`. Les exemples d’appels directs supposent un jeton fourni à un client
API autorisé dans la variable `MEDHEAD_ACCESS_TOKEN`. Le parcours web gère ces
jetons côté serveur ; ils ne sont pas exposés dans le navigateur.

```bash
curl -H "Authorization: Bearer $MEDHEAD_ACCESS_TOKEN" http://localhost:8080/api/v1/hospitals
curl -H "Authorization: Bearer $MEDHEAD_ACCESS_TOKEN" http://localhost:8080/api/v1/specialties
```

Les deux routes renvoient `200 OK` avec un tableau JSON trié par nom, puis par
code en cas d’égalité. Un catalogue vide renvoie `[]`.

`GET /api/v1/hospitals` fournit `id`, `code`, `name`, `latitude` et `longitude`
pour chaque hôpital, y compris ceux sans lit disponible. Les coordonnées sont
en degrés WGS84. Cette route sert à placer les hôpitaux sur la carte ; elle ne
recherche pas un hôpital et ne réserve aucun lit.

`GET /api/v1/specialties` fournit `code` et `name` pour toutes les spécialités
du référentiel, même celles sans hôpital associé. Le front-end affiche `name`
et transmet `code` lors de la réservation.

Les listes reflètent les données présentes dans la base : normalement les
12 hôpitaux de démonstration et les 80 spécialités. Les hôpitaux du jeu de charge
apparaissent aussi si ce jeu a été chargé dans la même base.

## Réservation d'un lit et outbox

L'API réserve un lit dans l'hôpital disponible le plus proche possédant la
spécialité demandée. La sélection utilise une requête PostGIS paramétrée.
Pour effectuer une réservation, le jeton doit posséder le rôle `REGULATEUR` :

```bash
curl --request POST http://localhost:8080/api/v1/hospitals/reservations \
  --header "Authorization: Bearer $MEDHEAD_ACCESS_TOKEN" \
  --header "Idempotency-Key: $(uuidgen)" \
  --header 'Content-Type: application/json' \
  --data '{
    "latitude": 51.5074,
    "longitude": -0.1278,
    "specialtyCode": "CARDIOLOGY"
  }'
```

Une réservation réussie renvoie `201 Created`, avec les informations de
l'hôpital, `reservationId`, `reservedAt` et le nombre de lits disponibles
**après** réservation, ainsi que la distance en mètres. La capacité totale
reste inchangée. Des critères invalides ou une spécialité inconnue renvoient
`400 Bad Request` ; l'absence de lit disponible renvoie `404 Not Found`.

### Idempotence des demandes

L'en-tête `Idempotency-Key` est obligatoire : le client génère un UUID par
demande de réservation et renvoie **le même UUID** s'il répète cette demande,
par exemple après un délai dépassé. Une clé absente ou qui n'est pas un UUID
renvoie `400 Bad Request` sans rien réserver.

- Une nouvelle clé crée une nouvelle réservation.
- Une clé déjà acceptée, avec les mêmes coordonnées et la même spécialité,
  renvoie la réponse d'origine (`201 Created`, même `reservationId`) avec
  l'en-tête `Idempotent-Replayed: true`. Aucun lit n'est consommé et aucun
  événement n'est ajouté.
- Une clé déjà acceptée pour une autre demande renvoie
  `422 Unprocessable Content` (`urn:problem:idempotency-key-reused`).
- Une demande refusée (spécialité inconnue, aucun lit) n'enregistre rien : elle
  peut être renvoyée avec la même clé.

La clé est rattachée à l'utilisateur authentifié (sujet `sub` du JWT) : la même
clé envoyée par un autre compte correspond à une autre demande et ne renvoie
jamais la réservation d'un tiers. Deux envois simultanés de la même clé sont
sérialisés par un verrou PostgreSQL de transaction
(`pg_advisory_xact_lock`) : le second attend la fin du premier puis renvoie sa
réservation. La clé primaire `(requested_by, idempotency_key)` de la table
`reservation_request` garantit en dernier recours qu'une clé ne crée qu'une
réservation.

Chaque ligne de `reservation_request` conserve le demandeur, la clé, une
empreinte SHA-256 de la demande (les coordonnées ne sont pas stockées en clair),
la réservation et la réponse renvoyée. Ces lignes ne sont pas purgées dans le
POC ; elles servent aussi de trace de l'auteur de chaque réservation.

### Transaction

Une seule transaction PostgreSQL effectue les quatre écritures :

1. Décrémenter `hospital.available_beds` de 1.
2. Enregistrer la réservation dans `bed_reservation` avec un UUID.
3. Enregistrer un événement `BedReserved` dans `event_outbox`.
4. Enregistrer la demande dans `reservation_request`.

La décrémentation est conditionnelle (`available_beds > 0`) et verrouille la
ligne de l'hôpital jusqu'à la fin de la transaction. Si une demande concurrente
a pris le dernier lit entre la recherche et la mise à jour, le service relance
la recherche pour réserver dans le prochain hôpital disponible. Une erreur,
y compris à l'insertion de l'outbox ou de la demande, annule toutes les écritures.

L'outbox contient un UUID d'événement, le type, l'UUID de réservation
(`aggregate_id`), la date et un payload JSON avec `eventId`, `reservationId`,
`hospitalId`, `hospitalCode`, `specialtyCode` et `occurredAt`. `published_at` reste
à `NULL` tant que l'événement attend sa publication. Pour les consulter :

```sql
SELECT id, event_type, aggregate_id, occurred_at, payload
FROM event_outbox
WHERE published_at IS NULL
ORDER BY occurred_at, id;
```

Le profil Spring `redis`, activé automatiquement dans Docker, publie les
événements en arrière-plan. Un trigger PostgreSQL émet une notification
`medhead_outbox` après le commit d'une insertion dans l'outbox. Le dispatcher
l'écoute sur une connexion dédiée et enchaîne des lots de 100 événements
jusqu'à épuisement, puis reste bloqué en attente d'une notification, sans
interrogation périodique de la table. Il s'abonne avant de lire les événements
déjà présents au démarrage ou après une reconnexion.
`MEDHEAD_OUTBOX_BATCH_SIZE` (de 1 à 1 000) règle la taille des lots.
`MEDHEAD_OUTBOX_RETRY_DELAY` (millisecondes, 1 000 par défaut) règle uniquement
l'attente avant une nouvelle tentative après une erreur ou lorsqu'un événement
en attente est verrouillé par un autre publieur. Il remplace l'ancien réglage
`MEDHEAD_OUTBOX_POLL_DELAY`.
`MEDHEAD_OUTBOX_ENABLED=false` suspend la publication tout en conservant
l'enregistrement des réservations et des événements dans PostgreSQL.

Chaque événement est lu dans sa propre transaction avec `FOR UPDATE SKIP LOCKED`
pour que plusieurs instances de l'API puissent publier sans sélectionner la
même ligne simultanément. Le publieur envoie l'événement en TLS, attend la
confirmation de `XADD`, puis renseigne `published_at`. Les lignes publiées sont
conservées dans PostgreSQL. En cas d'échec, la transaction de cet événement est
annulée et une nouvelle tentative est différée ; les publications
précédentes du lot restent validées.

La livraison suit une garantie **au moins une fois** : si Redis accepte l'envoi
mais que l'API tombe avant le commit PostgreSQL, l'événement sera renvoyé avec
le même `eventId`. Chaque futur consommateur devra utiliser cet identifiant
pour rendre son traitement idempotent.

Les champs du message Redis sont `eventId`, `type`, `aggregateId`, `occurredAt`
et `payload`. `payload` contient le JSON enregistré dans l'outbox et `type`
vaut `BedReserved` pour une réservation. Pour lire les derniers événements :

```bash
docker compose exec redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --tls --cacert /tls/ca.crt --sni redis -h redis XREVRANGE medhead:reservations + - COUNT 5'
```

La réservation et la disponibilité de l'API ne dépendent pas du succès de cet
envoi. `/actuator/health/readiness` vérifie l'API et PostgreSQL et sert au
healthcheck Docker. `/actuator/health` inclut aussi Redis et signale ses pannes.

## Configuration Redis Streams

Redis `8.2.10` utilise le réseau Docker `backend`, sans port publié sur la
machine hôte. Le publieur utilise l'adresse interne `redis:6379`.

### Transport TLS et authentification

Le protocole Redis (RESP) est transporté sur TLS 1.2 ou TLS 1.3. Le port sans
TLS est désactivé (`port 0`). Le client doit vérifier le certificat du serveur
avec l'autorité locale `ca.crt`, puis s'authentifier avec `REDIS_PASSWORD`.
Les certificats clients ne sont pas requis : l'authentification utilise le
mot de passe, ce qui garde la configuration du POC simple.

Le service ponctuel `redis-tls` génère automatiquement une autorité locale et
un certificat serveur valide un an, pour les noms `redis`, `localhost` et
l'adresse `127.0.0.1`. Il conserve les certificats dans le volume `redis-tls`
et réutilise ceux déjà présents. La clé privée du serveur n'est lisible que
par l'utilisateur Redis ; la clé privée de l'autorité est supprimée après
signature. Aucun certificat ou secret généré n'est ajouté au dépôt ou à
l'image Docker. Le certificat public de l'autorité est également copié dans
le volume `redis-ca`, monté en lecture seule dans l'API. Les clés privées ne
sont pas montées dans l'API.

Le profil Spring `redis` configure Lettuce avec le bundle SSL `redis`, qui
utilise `REDIS_CA_CERT` (`file:/etc/redis-ca/ca.crt` dans Docker). La chaîne de
certification et le nom du serveur sont vérifiés. Les délais de connexion et
de réponse sont de deux secondes. Pour lancer l'API hors Docker avec Redis,
activer ce profil et fournir `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` et le
chemin du certificat public via `REDIS_CA_CERT`. Sans le profil, la publication
est désactivée et les événements restent dans PostgreSQL.

Pour vérifier la connexion TLS authentifiée :

```bash
docker compose exec redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --tls --cacert /tls/ca.crt --sni redis -h redis PING'
```

La réponse attendue est `PONG`. Le mot de passe de démonstration se règle dans
`.env` via `REDIS_PASSWORD`.

### Persistance et mémoire

Le volume `redis-data` conserve les données sous `/data`, y compris les streams
et l'état des groupes de consommateurs. AOF est activé avec
`appendfsync always` : les écritures sont synchronisées sur disque avant la
réponse. Ce choix privilégie la durabilité au débit, adapté au POC. Il évite la
fenêtre de perte d'environ une seconde de `appendfsync everysec`, qui pourrait
faire disparaître un événement déjà marqué comme publié dans l'outbox.

Des snapshots RDB sont également effectués toutes les cinq minutes si les
données ont changé. `REDIS_MAXMEMORY` fixe la limite mémoire (256 Mio par
défaut), avec `maxmemory-policy noeviction` : Redis refuse les nouvelles
écritures quand la limite est atteinte au lieu de supprimer le stream.
Le publieur laisse l'événement dans l'outbox lorsqu'un envoi échoue.
Un volume conserve les données lors d'un redémarrage ou d'un `docker compose
down`, mais ne constitue pas une sauvegarde contre la perte du disque hôte.

### Rétention du stream et MAXLEN

`REDIS_STREAM_KEY` fixe le nom `medhead:reservations` et
`REDIS_STREAM_MAXLEN` fixe une cible de 10 000 événements. Ces valeurs sont
transmises à l'API via `medhead.events.stream.key` et
`medhead.events.stream.max-length`.

`MAXLEN` n'est pas une option globale de `redis.conf` : le publieur la passe
à chaque écriture :

```text
XADD medhead:reservations ACKED MAXLEN ~ 10000 * eventId UUID type BedReserved payload JSON
```

Le `~` applique une limite approximative, plus efficace qu'une suppression à
chaque ajout. `ACKED` (disponible depuis Redis 8.2) protège les événements qui
n'ont pas été acquittés par tous les groupes de consommateurs existants. Si
un groupe prend du retard, la longueur peut dépasser la cible. Sans groupe,
`ACKED` ne protège rien. Le publieur crée donc tous les groupes configurés avant
chaque envoi et ignore uniquement l'erreur indiquant qu'un groupe existe déjà.
La limite représente un nombre d'entrées, pas une taille mémoire
ou une durée. La persistance AOF ne restaure pas les entrées supprimées par
la rétention.

`REDIS_STREAM_CONSUMER_GROUPS` configure la liste des groupes, séparés par des
virgules (par défaut `reservation-consumers`). Ils commencent à l'offset `0-0`
pour inclure les événements déjà présents dans le stream. Prévoir un groupe
par service qui doit recevoir tous les événements ; les instances d'un même
service partagent leur groupe. Aucun consommateur métier n'est encore démarré,
donc les événements restent non acquittés et la cible MAXLEN peut être dépassée.

### Renouvellement des certificats locaux

Lorsqu'un certificat expire, arrêter les services puis supprimer uniquement
le volume de certificats avant de redémarrer :

```bash
docker compose down
docker volume rm medhead_redis-tls
docker compose up --build
```

Le volume `redis-data` et la base PostgreSQL restent conservés. Une nouvelle
autorité est générée et son certificat public est copié dans `redis-ca` avant
le redémarrage de l'API, qui charge ainsi la nouvelle autorité.
La commande globale `docker compose down -v` supprime aussi toutes les données.

## Tests

Les tests sans base de données se lancent avec :

```bash
./mvnw test
```

Ils incluent une règle d'architecture vérifiée par ArchUnit
(`ArchitectureTest`). Dans le module `hospital`, les dépendances vont dans un
seul sens : `api` → `application` → `persistence` → `domain`. La couche
`application` reçoit une commande (`ReserveBedCommand`) et renvoie ses propres
résultats (`ReservedBed`, `HospitalSummary`, `SpecialtySummary`). Les DTO HTTP
et leur conversion restent dans `api`. Une dépendance dans l'autre sens fait
échouer le build.

Les tests PostgreSQL utilisent une base PostGIS dédiée, éphémère, sur le port
`55432`. Ils vérifient la transaction, le rollback en cas d'échec de l'outbox,
la réservation concurrente du dernier lit et la sélection de l'hôpital suivant.
Ils vérifient aussi l'idempotence : rejeu d'une même clé sans second lit, refus
d'une clé réutilisée pour une autre demande, clé propre à chaque utilisateur,
nouvel essai possible après un refus et envois simultanés d'une même clé.
Ils couvrent aussi les catalogues d’hôpitaux et de spécialités : coordonnées,
tri, listes vides et absence de réservation lors de la lecture.

```bash
docker compose -f docker-compose.test.yaml up --build -d --wait database
./mvnw -Dmedhead.integration-tests=true test
docker compose -f docker-compose.test.yaml down
```

Pour lancer également les tests Redis TLS, le Compose de test expose Redis
uniquement sur `127.0.0.1:56379`. Copier son certificat public avant les tests :

```bash
docker compose -f docker-compose.test.yaml up --build -d --wait
mkdir -p .local/redis-tests
docker compose -f docker-compose.test.yaml cp redis:/tls/ca.crt .local/redis-tests/ca.crt
./mvnw -Dmedhead.integration-tests=true -Dmedhead.redis-integration-tests=true test
docker compose -f docker-compose.test.yaml down -v
```

Ces tests couvrent la publication TLS, le refus d'un certificat non fiable,
la reprise après erreur d'authentification, le rollback, l'exclusion des
transactions non validées, les publications concurrentes et la rétention
`ACKED`. Ils vérifient aussi qu'un échec du commit PostgreSQL après l'envoi
Redis produit un doublon avec le même `eventId` lors de la reprise.

Pour utiliser d'autres ports, définir `TEST_POSTGRES_PORT` et `TEST_REDIS_PORT`
lors du démarrage Docker, puis passer les valeurs correspondantes via
`-Dmedhead.test.jdbc-url=jdbc:postgresql://localhost:PORT/hospital_test` et
`-Dmedhead.test.redis-port=PORT`. `-Dmedhead.test.redis-ca=file:/chemin/ca.crt`
permet d'utiliser un autre emplacement de certificat public.

## Intégration continue

Le workflow GitHub Actions [Backend CI](.github/workflows/ci.yml) exécute deux
jobs, `frontend-ci` et `backend-ci`, pour les pull requests ciblant `master` et
les push sur `master`. Il peut aussi être lancé manuellement depuis l'onglet
**Actions**, une fois le workflow présent sur la branche par défaut.

Le job `frontend-ci` utilise Node 24 : il installe les dépendances avec `npm ci`,
lance les tests, la vérification Svelte et la compilation, puis construit les
deux images Docker du front-end (cibles `runtime` et `dev`).

Le job `backend-ci` utilise Ubuntu 24.04, Java 21 Temurin et le Maven Wrapper du dépôt,
avec un cache des dépendances Maven. Il est limité à 20 minutes et dispose
uniquement de la permission `contents: read`. Les services utilisent les
identifiants de test du Compose ; aucun secret GitHub ou fichier `.env`
personnel n'est nécessaire.

Les étapes s'exécutent dans cet ordre :

1. Récupérer le code et préparer Java et le cache Maven.
2. Construire et démarrer PostGIS et Redis TLS avec
   `docker-compose.test.yaml`, puis attendre leur disponibilité. L'attente
   après démarrage est limitée à 120 secondes.
3. Copier le certificat public de Redis dans `.local/redis-tests/ca.crt`.
4. Exécuter `clean verify` avec les deux propriétés d'intégration activées :
   les tests unitaires et d'intégration sont exécutés, puis le JAR est créé.
5. Vérifier les rapports JUnit de `HospitalCatalogIntegrationTest`,
   `BedReservationIntegrationTest` et `OutboxPublicationIntegrationTest`.
   Le job échoue si un rapport manque,
   si une suite n'a exécuté aucun test ou si elle contient des tests ignorés,
   des échecs ou des erreurs.
6. Construire l'image `medhead:ci` avec le Dockerfile du dépôt. Sa construction
   intervient seulement après la réussite des tests et du contrôle des rapports.

Chaque exécution utilise un nom de projet Compose contenant l'identifiant
de l'exécution et son numéro de tentative, pour isoler ses conteneurs et
volumes. Le nettoyage s'exécute avec `always()` après les étapes de
diagnostic, y compris lorsqu'une étape échoue : il arrête les services et
supprime uniquement les volumes de cet environnement de test.

Les artefacts suivants sont téléchargeables depuis la page de l'exécution
GitHub Actions pendant sept jours :

| Artefact | Contenu | Disponibilité |
| --- | --- | --- |
| `backend-test-reports` | Rapports XML et texte de `target/surefire-reports/` | Même après un échec, si Maven a produit des rapports |
| `backend-jar` | JAR de l'application | Après un `clean verify` réussi, même si la construction Docker échoue ensuite |
| `backend-docker-diagnostics` | État des conteneurs et logs Compose | En cas d'échec du job avant le nettoyage |

Le Dockerfile recompile actuellement l'application avec `-DskipTests`.
Cette seconde compilation suit la validation complète réalisée par Maven
dans le job. L'image est construite localement sur le runner ; sa publication,
le déploiement et les tests de charge feront l'objet de workflows distincts.

### Reproduire les étapes principales en local

Prérequis : Java 21 et Docker avec le plugin Compose. Les ports `55432` et
`56379` doivent être libres. Utiliser un nom de projet distinct pour éviter
de toucher aux services de démonstration ou à un autre environnement de test :

```bash
export COMPOSE_PROJECT_NAME=medhead-ci-local
docker compose -f docker-compose.test.yaml up --build -d --wait --wait-timeout 120
mkdir -p .local/ci-local
docker compose -f docker-compose.test.yaml cp redis:/tls/ca.crt .local/ci-local/ca.crt
./mvnw --batch-mode --no-transfer-progress \
  -Dmedhead.integration-tests=true \
  -Dmedhead.redis-integration-tests=true \
  -Dmedhead.test.redis-ca=file:./.local/ci-local/ca.crt \
  clean verify
```

Si Maven réussit, construire l'image avec `docker build --tag medhead:ci .`.
En cas d'échec, consulter les rapports dans `target/surefire-reports/` et
les logs avec `docker compose -f docker-compose.test.yaml logs --no-color`.
Terminer dans les deux cas par :

```bash
docker compose -f docker-compose.test.yaml down --volumes --remove-orphans
unset COMPOSE_PROJECT_NAME
```

### Validation avant fusion

Sur une première pull request, vérifier une exécution réussie puis provoquer
temporairement un échec de test. Le job doit devenir rouge, rendre les rapports
et diagnostics disponibles et exécuter le nettoyage. Retirer ensuite cette
modification et obtenir une nouvelle exécution réussie. Une fois le contrôle
visible sur GitHub, ajouter `backend-ci` aux contrôles obligatoires de la règle
de protection de `master` pour conditionner la fusion à sa réussite.

## Données de démonstration

Le seeding normal est idempotent : il remet à jour le nom, la position et la
capacité des hôpitaux de démonstration, mais conserve leur nombre courant de
lits disponibles. Il peut être rejoué manuellement avec :

```bash
docker compose run --rm seed
```

Pour supprimer puis recréer les 12 hôpitaux avec leur disponibilité initiale :

```bash
docker compose --profile maintenance run --rm reset-seed
```

Cette seconde commande est volontairement destructive pour les hôpitaux dont
le code commence par `DEMO-`. Elle ne touche ni aux spécialités NHS, ni aux
autres hôpitaux. Les réservations de ces hôpitaux sont également supprimées ;
leurs événements outbox restent conservés.

## Données de test de charge

Un jeu distinct de 2 000 hôpitaux synthétiques permet de tester les performances
sans rendre le jeu de démonstration illisible :

```bash
docker compose --profile performance run --rm seed-load
```

Comme pour le jeu de démonstration, rejouer la commande conserve les
disponibilités courantes. Pour supprimer uniquement ces données de charge :

```bash
docker compose --profile maintenance run --rm clear-load
```

Les réservations des hôpitaux de charge supprimés sont également supprimées,
mais leurs événements outbox restent conservés.

Les scénarios k6 et leur environnement isolé sont décrits dans le
[guide des tests de charge](tests/performance/README.md). Ils couvrent les
réservations réussies et les refus faute de lit, à 80 req/s avec un pic à
800 req/s et un objectif p95 inférieur à 200 ms.

Pour démarrer également pgAdmin :

```bash
docker compose --profile tools up --build
```

pgAdmin est alors accessible sur <http://localhost:5050>. Pour enregistrer la
base, utiliser `database` comme nom d'hôte et `5432` comme port.

Arrêter les conteneurs en conservant les données :

```bash
docker compose down
```

Supprimer également les volumes et toutes les données locales :

```bash
docker compose down -v
```
