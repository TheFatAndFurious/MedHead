# Tests de charge des réservations

Prérequis : Docker Compose 2.24.4 ou supérieur (`!override`), Python 3 et k6.
Le runner utilise `fcntl` et fonctionne sous macOS/Linux.
La stratégie et les hypothèses sont décrites dans
[`strategie-tests-charge.md`](../../docs/performance/strategie-tests-charge.md).
Les résultats commités sont dans [`docs/performance/`](../../docs/performance/).

## Avant une campagne

- Exécuter la campagne seule sur la machine : aucun autre conteneur, build
  ou agent en parallèle, machine branchée sur secteur.
- Partir d'un arbre Git propre : le runner relève le commit et l'état du
  dépôt (`working_tree_clean` dans `verdict.json`). Un résultat mesuré avec
  des modifications locales ne peut pas être rattaché à un SHA.
- Choisir un nom de projet Compose propre à la campagne, par exemple
  `export COMPOSE_PROJECT_NAME=medhead-lot24`. Sans cette variable, le projet
  s'appelle `medhead-performance`. Ne pas reprendre le nom d'un environnement
  existant.

## Validation courte

Depuis la racine du dépôt :

```sh
python3 tests/performance/run.py success --smoke
python3 tests/performance/run.py mixed --smoke
```

Chaque commande démarre la pile de performance, attend l'API, remet à
zéro uniquement sa base `hospital_performance` et son stream Redis, puis envoie
environ 100 requêtes en dix secondes. Le scénario mixte répartit le débit également
entre réservations et refus. Ce contrôle valide la chaîne de bout en bout
(jeton, TLS, réservation, outbox, cohérence). Il démarre sur une JVM froide :
ses verdicts (a) et (b) sont affichés à titre indicatif, et seul le seuil p95
de k6 reste actif. Ce n'est pas une validation du pic à 800 req/s.

L'API dédiée est exposée sur `127.0.0.1:58080`. PostgreSQL et Redis ne publient
aucun port. Les volumes appartiennent au projet Docker de la campagne.
Keycloak utilise `http://localhost:58180`, avec une base et un realm dédiés à
cette pile. Le runner obtient un JWT via le compte de service `medhead-load`
(secret local `medhead_perf_only`) et le transmet à k6 et à la collecte Actuator.
Ce client est défini uniquement dans `tests/performance/keycloak`, sans compte
humain ni flux mot de passe. Son jeton de 30 minutes couvre la campagne de
17 minutes ; le runner refuse un profil dont la durée dépasserait le jeton.
Les mesures incluent la vérification des JWT par l'API, et les connexions
PostgreSQL (`verify-full`) et Redis en TLS. La connexion et le renouvellement
des sessions web ne sont pas mesurés.
Chaque itération envoie une nouvelle clé `Idempotency-Key` (`crypto.randomUUID()`) :
les mesures incluent donc le verrou et l'enregistrement de la demande, sans
rejeu. Le contrôle SQL final vérifie une ligne `reservation_request` par
réservation.
Ne pas lancer d'autres clients sur cette API pendant une campagne.

## Ressources allouées (« par instance »)

L'exigence porte sur **une instance** de service. Le Compose de performance
démarre une seule API et fixe explicitement les limites de chaque conteneur :

| Conteneur | CPU | Mémoire | Remarque |
| --- | --- | --- | --- |
| `api` | 2 | 1 Gio | Instance mesurée ; tas JVM jusqu'à 75 % (`-XX:MaxRAMPercentage=75`) |
| `database` (PostGIS) | 2 | 2 Gio | Réglages PostgreSQL par défaut |
| `redis` | 1 | 512 Mio | `maxmemory` 256 Mo, stream limité à 10 000 entrées |
| `keycloak` | 1 | 1 Gio | Émet un seul jeton et ses clés pendant la campagne |
| `identity-database` | 0,5 | 256 Mio | Base de Keycloak |
| `event-consumer` | 0,5 | 128 Mio | Consommateur de démonstration : acquitte les événements, résumé toutes les 10 s |

La limite CPU de PostgreSQL se règle par `PERF_DATABASE_CPUS` (2 par défaut,
configuration de référence), par exemple pour vérifier si l'API tient la charge
quand la base n'est plus le facteur limitant :

```sh
PERF_DATABASE_CPUS=4 python3 tests/performance/run.py success
```

`verdict.json` enregistre les limites effectives de chaque conteneur.
Les autres réglages de l'API restent ceux par défaut (Tomcat, pool HikariCP
de 10 connexions, lots outbox de 100 événements). k6 tourne sur l'hôte, sans
limite : il dispose des cœurs restants. Sur une machine de 10 cœurs, les
limites ci-dessus en réservent 6,5 aux conteneurs.

## Campagnes complètes

```sh
python3 tests/performance/run.py success
python3 tests/performance/run.py mixed
```

| Phase | Débit d'arrivée | Durée | Requêtes prévues |
| --- | --- | --- | --- |
| Échauffement | Rampe de 0 à 80 req/s | 2 min | 4 800 |
| Nominal | 80 req/s | 5 min | 24 000 |
| Pic | 800 req/s | 5 min (`PEAK_DURATION=300`) | 240 000 |
| Récupération | 80 req/s | 5 min | 24 000 |

Chaque campagne dure environ dix-sept minutes et prévoit 292 800 requêtes.
Les campagnes longues se lancent de préférence en arrière-plan, sortie
redirigée vers un fichier.
Le pic se règle par variable d'environnement ou option :

```sh
PEAK_DURATION=60 python3 tests/performance/run.py success   # ancien pic d'une minute
python3 tests/performance/run.py success --peak-rate 400   # palier intermédiaire
```

Le runner transmet `PEAK_RATE` et `PEAK_DURATION` à k6, qui calcule les mêmes
phases. Pour `k6 run` lancé seul, ces variables gardent les mêmes valeurs par défaut.

Les données sont recréées avant chaque campagne par `prepare.sql`. Elles
reprennent les 2 000 emplacements du jeu `seed-load` (mêmes formules de
coordonnées), mais avec une capacité suffisante : `seed-load` ne prévoit que
1 à 20 lits par hôpital, et rejouer ce script conserve les lits restants.
Les 1 600 premiers hôpitaux portent la spécialité `PERF_AVAILABLE` et autant
de lits que nécessaire pour toutes les réservations prévues, plus 20 %
(220 lits chacun en scénario « réservations réussies » avec un pic de
5 minutes, 110 en mixte). Les 400 autres n'ont aucun lit et portent
`PERF_UNAVAILABLE`, ce qui empêche les demandes prévues en `404` de réserver
dans un autre hôpital. Le seed de démonstration n'est pas utilisé.

Le runner empêche deux campagnes simultanées lancées depuis ce dépôt.

## Deux verdicts séparés

Le runner calcule deux verdicts distincts, enregistrés dans `verdict.json`
et affichés en fin de campagne. Le second ne masque pas le premier.

**(a) Latence de l'API** : le critère de l'exigence « moins de 200 ms jusqu'à
800 requêtes par seconde, par instance ». Pour chaque phase mesurée (nominal,
pic, récupération ; l'échauffement est exclu) :

- p95 **et** p99 de `http_req_duration` strictement inférieurs à 200 ms,
  pour la phase entière et pour chaque flux métier (réservations, refus) ;
- 0 % d'erreur HTTP technique (statut inattendu, délai de 2 s dépassé, erreur
  réseau) ;
- toutes les requêtes prévues envoyées, aucune itération abandonnée
  (`dropped_iterations`), et réponses métier conformes (`checks`).

**(b) Santé de l'outbox** (critère secondaire) : à la fin de la récupération,
plus aucun événement en attente, et un événement publié par réservation.
Aucun délai chiffré de publication n'est spécifié : le runner rapporte, par
phase, le nombre maximal d'événements en attente, l'âge du plus ancien, les
percentiles du retard de publication (`published_at - occurred_at`) et le
temps de résorption après le pic, sans les juger.

La cohérence des données est contrôlée à part par `verify.sql`, en deux temps :

- **cohérence interne**, toujours exigée : aucun lit négatif ni surréservation,
  lits consommés = réservations, une demande enregistrée et un événement
  `BedReserved` par réservation, payloads conformes ;
- **rapprochement avec les réponses `201`** reçues par k6, rapporté sans
  bloquer : une requête abandonnée par le client après 2 s peut avoir été
  validée par l'API, ce qui crée un écart sans corruption. Un tel écart
  s'accompagne d'erreurs et fait donc déjà échouer le verdict (a).

Une sortie non nulle indique un verdict (a) ou (b) en échec, une incohérence
interne, un seuil k6 dépassé ou un échec de collecte. Pour un smoke, seuls la
cohérence, la collecte et les seuils k6 comptent.

Le dispatcher est réveillé par PostgreSQL avec `LISTEN / NOTIFY` et enchaîne
les lots sans délai tant qu'il reste des événements disponibles. La taille
des lots reste de 100 événements.

## Résultats

Les résultats bruts sont conservés dans `.local/performance/<date>-<mode>/`
(non versionné) :

- `k6.txt`, `summary.json` : débits, latences et seuils par phase et flux.
- `verdict.json` : environnement (hôte, Docker, limites des conteneurs,
  versions, commit, état du dépôt), profil, verdicts (a) et (b) avec les
  tableaux par phase, cohérence et synthèse des ressources par phase.
- `consistency.txt` : cohérence interne (lits consommés, réservations,
  demandes et événements, identifiants et payloads) et rapprochement avec
  les réponses `201`.
- `outbox.csv` : nombre et âge des événements en attente, toutes les cinq secondes.
- `outbox-metrics.csv` : durées des lots et des étapes, résultats et débits
  mesurés via Actuator, avec une référence avant la charge et un dernier
  relevé après la fin des requêtes.
- `resources.csv` : relevé `docker stats --no-stream` toutes les cinq secondes
  (CPU, mémoire et limite, processus) pour chaque conteneur, plus le processus
  k6 de l'hôte relevé avec `ps`. Le CPU s'exprime en pourcentage d'un cœur :
  200 % correspond aux deux CPU de l'API.
- `compose.yaml`, `startup.txt`, `k6-version.txt`, `working-tree.txt`.
  La configuration locale peut contenir des mots de passe de démonstration :
  examiner ces fichiers avant de partager un rapport.

Pour une campagne de référence, copier les `summary.json` et `verdict.json`
dans `docs/performance/resultats-AAAA-MM-JJ/` et rédiger le rapport associé.
Les CSV, volumineux, restent locaux.

Le nombre d'événements en attente utilisé pour le verdict est relevé avant
les contrôles SQL, qui peuvent être longs, pour ne pas ajouter leur durée à
la fenêtre de récupération. Le retard de publication par phase est rattaché à
l'instant d'occurrence de l'événement ; les bornes des phases sont calculées
depuis l'heure de lancement de k6, à une seconde près.

Le contrôle SQL rapproche les réservations et événements en base ; il ne
valide pas individuellement la présence des événements dans Redis, dont la
rétention peut supprimer des entrées. Le suivi de l'outbox vérifie leur
publication déclarée. Aucun consommateur métier n'est simulé, mais le
consommateur de démonstration `event-consumer` lit et acquitte les événements
du groupe `reservation-consumers`, ce qui permet à la rétention `ACKED` de
réduire le stream. Les campagnes du 22 novembre 2026 ont été menées sans lui.

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
| `outbox.redis.group.duration` | Tentative de création du groupe, réponse `BUSYGROUP` comprise ; au premier envoi et après la disparition du stream seulement | `created`, `existing`, `error` |
| `outbox.redis.add.duration` | `XADD` jusqu'à la confirmation Redis | `success`, `missing` (stream supprimé : groupes recréés, puis nouvel envoi), `error` |

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
trafic métier mesuré par k6. Une API surchargée peut ne pas y répondre dans
les 2 s : ces échecs sont comptés dans `verdict.json` (`actuator_errors`) et
le relevé de l'outbox continue. Un relevé SQL en échec ou une remise à zéro
des compteurs (redémarrage de l'API) rend en revanche la campagne non valide.

Pour diagnostiquer les ressources et les erreurs pendant une campagne :

```sh
docker stats
docker compose -f docker-compose.performance.yaml logs -f api database redis
```

Les métriques JVM, les attentes de verrous PostgreSQL et les latences Redis
nécessitent une instrumentation complémentaire ; elles ne sont pas collectées
automatiquement. Les chiffres obtenus avec Docker local dépendent des
ressources partagées avec le générateur.

## Arrêt

```sh
docker compose -f docker-compose.performance.yaml down      # conserve les volumes
docker compose -f docker-compose.performance.yaml down -v   # supprime aussi les volumes
```

Le prochain lancement du runner réinitialise les données de performance. Les
données habituelles de MedHead ne sont pas modifiées.

## Exécution k6 seule

Après préparation par le runner, il est aussi possible de lancer directement :

```sh
k6 run -e MODE=mixed -e SMOKE=1 tests/performance/reservations.js
```

`MODE` accepte `success` ou `mixed` ; `PEAK_RATE` et `PEAK_DURATION` règlent
le pic. `BASE_URL` permet de viser un autre hébergement de l'API ; dans ce
cas, la préparation et les contrôles de base restent à organiser
explicitement. L'exécution k6 seule ne remet pas les lits à disposition, ne
vérifie pas la cohérence finale en base et ne produit pas les verdicts (a) et (b).

## Tests du runner

```sh
cd tests/performance && python3 -m unittest
```
