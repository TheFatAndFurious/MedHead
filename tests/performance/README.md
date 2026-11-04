# Tests de charge des réservations

Prérequis : Docker Compose 2.24.4 ou supérieur (`!override`), Python 3 et k6.
Le runner utilise `fcntl` et fonctionne sous macOS/Linux.
La stratégie et les hypothèses sont décrites dans
[`strategie-tests-charge.md`](../../docs/performance/strategie-tests-charge.md).

## Validation courte

Depuis la racine du dépôt :

```sh
python3 tests/performance/run.py success --smoke
python3 tests/performance/run.py mixed --smoke
```

Chaque commande démarre la pile `medhead-performance`, attend l'API, remet à
zéro uniquement sa base `hospital_performance` et son stream Redis, puis envoie
environ 100 requêtes en dix secondes. Le scénario mixte répartit le débit également
entre réservations et refus. Les seuils de latence restent actifs ; ce contrôle
court n'est pas une validation du pic à 800 req/s ni de la récupération.

L'API dédiée est exposée sur `127.0.0.1:58080`. PostgreSQL et Redis ne publient
aucun port. Les volumes appartiennent au projet Docker `medhead-performance`.
Ne pas changer le nom de projet pour celui d'un environnement existant.
Ne pas lancer d'autres clients sur cette API pendant une campagne.

## Campagnes complètes

```sh
python3 tests/performance/run.py success
python3 tests/performance/run.py mixed
```

Chaque campagne dure environ treize minutes : échauffement, 80 req/s pendant
cinq minutes, 800 req/s pendant une minute, puis 80 req/s pendant cinq minutes.
Les données sont recréées entre les deux campagnes. Les 1 600 hôpitaux avec
capacité offrent 160 000 lits ; 400 autres hôpitaux n'ont aucun lit disponible.
Deux spécialités synthétiques distinctes empêchent les demandes prévues en
`404` de réserver dans un autre hôpital. Le seed habituel n'est pas utilisé.

Le runner empêche deux campagnes simultanées lancées depuis ce dépôt.
Une sortie non nulle indique un seuil k6 dépassé, une incohérence des données,
un échec de collecte ou, pour une campagne complète, une outbox encore en
attente après la récupération. Le dispatcher est désormais réveillé par
PostgreSQL avec `LISTEN / NOTIFY` et enchaîne les lots sans délai tant qu'il
reste des événements disponibles. La taille des lots reste de 100 événements.
Les campagnes précédentes à 1 000 ms puis 100 ms servent de références pour
comparer le comportement de ce nouveau déclenchement.

## Résultats

Les résultats sont conservés dans `.local/performance/<date>-<mode>/` :

- `k6.txt`, `summary.json` : débits, latences et seuils par phase et flux.
- `consistency.txt` : rapprochement des réponses `201`, lits consommés,
  réservations et événements ; vérification des identifiants et payloads.
- `outbox.csv` : nombre et âge des événements en attente, toutes les cinq secondes.
- `outbox-metrics.csv` : durées des lots et des étapes, résultats et débits
  mesurés via Actuator, avec une référence avant la charge et un dernier
  relevé après la fin des requêtes.
- `verdict.json` : résultat k6 et état de récupération.
- Configuration Docker, version k6, commit et état du dépôt pour contextualiser
  les résultats. La configuration locale peut contenir des mots de passe de
  démonstration : examiner ces fichiers avant de partager un rapport.

Le nombre d'événements en attente utilisé pour le verdict est relevé avant
les contrôles SQL, qui peuvent être longs, pour ne pas ajouter leur durée à
la fenêtre de récupération.

Le contrôle SQL rapproche les réservations et événements en base ; il ne
valide pas individuellement la présence des événements dans Redis, dont la
rétention peut supprimer des entrées. Le suivi de l'outbox vérifie leur
publication déclarée. Aucun consommateur métier n'est simulé.

## Durées du publieur

L'exposition HTTP de `/actuator/metrics` est activée uniquement par le Compose
de performance. L'instrumentation utilise Micrometer fourni par Actuator,
sans nouveau service de supervision et sans log pour chaque événement.
L'instrumentation conserve le fonctionnement des transactions et de la persistance.

| Métrique | Mesure | Résultats distingués (`outcome`) |
| --- | --- | --- |
| `outbox.batch.duration` | Durée d'un passage du publieur | `full`, `empty`, `error` |
| `outbox.batch.size` | Nombre d'événements publiés et validés par passage | `full`, `empty`, `error` |
| `outbox.batch.gap` | Temps entre la fin d'un passage et le début du suivant | Aucun |
| `outbox.event.duration` | Appel au service transactionnel, ouverture, flush et commit compris | `published`, `empty`, `error` |
| `outbox.select.duration` | Sélection et verrouillage dans PostgreSQL | `found`, `empty`, `error` |
| `outbox.redis.group.duration` | Tentative de création du groupe, réponse `BUSYGROUP` comprise | `created`, `existing`, `error` |
| `outbox.redis.add.duration` | `XADD` jusqu'à la confirmation Redis | `success`, `error` |

`full` signifie que le passage a atteint sa limite de lot. `empty` signifie
qu'une recherche n'a trouvé aucun événement, même si le passage en a publié
avant cette recherche. `error` peut également concerner un lot partiellement
publié. Les événements déjà validés restent comptés dans `outbox.batch.size`.
Un envoi Redis réussi suivi d'un échec du commit compte comme `redis.add=success`
et `event=error`, jamais comme un événement publié et validé.
Les durées d'événement correspondent aux appels du dispatcher ; les
étapes internes sont aussi mesurées lors d'appels directs au service.

Dans `outbox-metrics.csv`, chaque ligne correspond à une métrique et un résultat :

- `count`, `total` et `max` sont les mesures Micrometer ; `count` et `total`
  sont cumulatifs depuis le démarrage de l'API. `max` est un maximum sur la
  fenêtre Micrometer, pas le maximum exact de chaque intervalle de collecte.
- `interval_count`, `interval_mean` et `interval_rate` sont calculés par
  différence avec le relevé précédent. La première ligne est une référence ;
  ses colonnes d'intervalle sont vides. Un redémarrage détecté par une baisse
  de compteur fait échouer la collecte.
- `base_unit` précise l'unité : les durées sont généralement en secondes
  (multiplier la moyenne par 1 000 pour obtenir des millisecondes), les tailles
  de lots en événements. `interval_rate` s'exprime en observations par seconde.

Pour le débit de publication, utiliser `outbox.event.duration` avec
`outcome=published`. Comparer la durée des lots à leur taille et au temps
d'attente `outbox.batch.gap`, puis les durées de sélection et d'opérations
Redis. Avec le réveil par notification, le gap inclut l'attente de travail
quand l'outbox est vide ; il doit être très court entre deux lots complets.
Il peut aussi inclure l'attente avant une nouvelle tentative après erreur.
Le reste de la durée transactionnelle inclut les traitements Java,
l'ouverture de transaction, le flush et le commit ; ce n'est pas une mesure
isolée du commit. Les relevés HTTP sont successifs et peuvent chevaucher les
frontières d'un événement ou d'une phase : raisonner sur plusieurs intervalles.
Ces mesures ne fournissent pas de p95 par étape.

Les relevés Actuator utilisent des requêtes HTTP supplémentaires, hors du
trafic métier mesuré par k6. Une erreur de collecte est conservée dans le
verdict et rend la campagne non valide.

Pour diagnostiquer les ressources et les erreurs pendant une campagne :

```sh
docker stats
docker compose -f docker-compose.performance.yaml logs -f api database redis
```

Les métriques JVM, les attentes de verrous PostgreSQL et les latences Redis
nécessitent une instrumentation complémentaire ; elles ne sont pas collectées
automatiquement par ce premier dispositif. Les chiffres obtenus avec Docker
local dépendent des ressources partagées avec le générateur.

## Arrêt

```sh
docker compose -f docker-compose.performance.yaml down
```

Cette commande conserve les volumes dédiés. Le prochain lancement du runner
réinitialise les données de performance. Les données habituelles de MedHead
ne sont pas modifiées.

## Exécution k6 seule

Après préparation par le runner, il est aussi possible de lancer directement :

```sh
k6 run -e MODE=mixed -e SMOKE=1 tests/performance/reservations.js
```

`MODE` accepte `success` ou `mixed`. `BASE_URL` permet de viser un autre
hébergement de l'API ; dans ce cas, la préparation et les contrôles de base
restent à organiser explicitement. L'exécution k6 seule ne remet pas les lits
à disposition et ne vérifie pas la cohérence finale en base.
