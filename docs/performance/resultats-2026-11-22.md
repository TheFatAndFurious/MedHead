# Résultats des tests de charge du 22 novembre 2026

Campagnes du lot 6 (issue #24), sur la version actuelle : authentification
JWT Keycloak, PostgreSQL en TLS `verify-full`, Redis en TLS, outbox réveillée
par `LISTEN / NOTIFY`. Elles remplacent les campagnes du 28 octobre, antérieures
à ces évolutions. Méthode et critères : [stratégie](strategie-tests-charge.md) ;
commandes : [guide d'exécution](../../tests/performance/README.md).

## Synthèse

| Campagne | Pic | (a) Latence de l'API | (b) Outbox | Cohérence interne |
| --- | --- | --- | --- | --- |
| Réservations réussies | 800 req/s, 5 min | **Échec** : saturation, 95 % d'erreurs au pic | **Échec** | Conforme |
| Mixte (50 % `201`, 50 % `404`) | 800 req/s, 5 min | **Échec** : p95 8,1 ms mais p99 1 005 ms, 0,21 % d'erreurs | **Échec** | Conforme |
| Réservations réussies (exploratoire) | 400 req/s, 5 min | **Réussi** : p95 6,0 ms, p99 15,1 ms | **Échec** | Conforme |

**Verdict face à l'exigence** « moins de 200 ms jusqu'à 800 requêtes par
seconde, par instance » : **non satisfaite** sur cet environnement. Une instance
d'API limitée à 2 CPU et 1 Gio, avec PostgreSQL limité à 2 CPU, tient
largement le seuil à 80 req/s (p99 inférieur à 10 ms) et à 400 réservations/s
(p99 de 15 ms). À 800 réservations/s, le serveur n'en valide qu'environ 410
par seconde : la file d'attente s'allonge et les requêtes dépassent le délai
client de 2 s. Avec 400 réservations et 400 refus par seconde, la latence
reste très basse, sauf pendant environ sept secondes en fin de pic, ce qui
suffit à porter le p99 du pic à 1 s.

L'outbox (verdict b) ne suit pas un débit soutenu de 400 réservations/s : elle
publie environ 140 à 215 événements/s, et son retard atteint plusieurs
minutes sans se résorber pendant les cinq minutes de récupération. Aucune
incohérence de données n'a été détectée : pas de surréservation, une demande
et un événement par réservation, même sous saturation.

## Environnement

| Élément | Valeur |
| --- | --- |
| Commit mesuré | `298a151ef81d140e9d6881515b2573493cccdc7e`, arbre propre (`working_tree_clean: true` dans chaque `verdict.json`) |
| Code de l'API | Identique à `master` `576edf0` (la branche ne modifie ni `src/`, ni `pom.xml`, ni `Dockerfile`), version `0.1.0-SNAPSHOT` |
| Hôte | Apple M4, 10 cœurs, 16 Gio de RAM, macOS 15.8.1, sur secteur |
| Docker | Docker Desktop, moteur 28.1.1, Compose 2.35.1 ; VM : 10 CPU, 15,6 Gio |
| Générateur | k6 v1.2.1 (go1.24.6, darwin/arm64), sur l'hôte, sans limite |
| Projet Compose | `medhead-lot24`, seul en exécution (aucun autre conteneur détecté, relevé toutes les 15 s) |
| Isolation | `docker compose down -v` avant chaque campagne complète : JVM, base et Redis neufs |

Limites des conteneurs (`docker-compose.performance.yaml`) :

| Conteneur | CPU | Mémoire |
| --- | --- | --- |
| `api` (instance mesurée) | 2 | 1 Gio, tas JVM jusqu'à 75 % |
| `database` (PostGIS 17) | 2 | 2 Gio |
| `redis` 8.2.10 | 1 | 512 Mio |
| `keycloak` 26.8.2 | 1 | 1 Gio |
| `identity-database` | 0,5 | 256 Mio |

Réglages applicatifs par défaut : Tomcat, pool HikariCP de 10 connexions,
lots outbox de 100 événements, stream Redis `MAXLEN ~ 10000`. Délai client k6 :
2 s, sans relance. Chaque requête porte un JWT et une clé `Idempotency-Key`
neuve.

## Profil de charge

| Phase | Débit d'arrivée | Durée | Requêtes prévues |
| --- | --- | --- | --- |
| Échauffement (exclu des verdicts) | Rampe de 0 à 80 req/s | 2 min | 4 800 |
| Nominal | 80 req/s | 5 min | 24 000 |
| Pic | 800 req/s (400 pour la campagne exploratoire) | 5 min | 240 000 (120 000) |
| Récupération | 80 req/s | 5 min | 24 000 |

Données : 2 000 hôpitaux aux emplacements du jeu `seed-load`, recréés avant
chaque campagne. Les 1 600 hôpitaux disponibles ont 220 lits chacun en
« réservations réussies » à 800 req/s, 110 en mixte et 130 à 400 req/s.

Ordre d'exécution (heures UTC) : smoke « réservations réussies » (13:45),
smoke mixte (13:46), réservations réussies à 800 req/s (13:47), mixte à
800 req/s (14:23), réservations réussies à 400 req/s (14:51).

## Validation courte (smoke)

Les deux smokes (10 req/s pendant 10 s) valident la chaîne de bout en bout :
jeton, TLS, réservations et refus attendus, outbox publiée et cohérence.

| Smoke | Requêtes | p50 | p95 | p99 | Erreurs | Événements publiés | Cohérence |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Réservations réussies | 101 | 16,1 ms | 32,9 ms | 264,7 ms | 0 % | 101 / 101 | Conforme, 101 `201` |
| Mixte | 101 | 21,6 ms | 45,0 ms | 278,9 ms | 0 % | 51 / 51 | Conforme, 51 `201` |

Sur une centaine de requêtes, le p99 correspond aux deux requêtes les plus
lentes, qui suivent la réinitialisation des données. Il est indicatif et
n'entre pas dans le verdict.

## (a) Latence de l'API

### Réservations réussies, pic à 800 req/s

| Phase | Débit cible | Requêtes | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nominal | 80 req/s | 24 001 | 80,0 req/s | 3,4 ms | 5,1 ms | 8,4 ms | 58,2 ms | 0,00 % |
| Pic | 800 req/s | 218 524 | 728,4 req/s | 1 998,8 ms | 2 000,2 ms | 2 003,7 ms | 6 500,0 ms | 95,12 % |
| Récupération | 80 req/s | 23 878 | 79,6 req/s | 3,4 ms | 1 991,6 ms | 2 000,0 ms | 2 015,3 ms | 5,12 % |

**Échec.** k6 a abandonné 21 600 itérations faute d'utilisateurs virtuels
disponibles (1 760 au maximum), et seules 37 % des vérifications métier sont
conformes. Les erreurs sont des dépassements du délai de 2 s, plus quelques
connexions fermées (`EOF`). La base contient 186 223 réservations pour
62 110 réponses `201` reçues : les requêtes abandonnées par le client ont
été validées plus tard par l'API.

Pendant le pic, l'API a validé 123 568 réservations, soit **412 par
seconde** en moyenne. C'est la capacité observée du serveur pour des
réservations réussies, avec ces limites. Les latences au-delà de 2 s ne sont
pas mesurées, puisque le client abandonne.

Retour à la normale : toutes les erreurs de la récupération surviennent
dans les 20 premières secondes après le pic, le temps d'écouler la file
d'attente. Le p50 de la récupération (3,4 ms) est celui du régime nominal ;
le p95 et le p99 sont dégradés par ces 5 % de requêtes.

### Mixte, pic à 800 req/s (400 réservations + 400 refus par seconde)

| Phase | Débit cible | Requêtes | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nominal | 80 req/s | 24 002 | 80,0 req/s | 4,0 ms | 7,3 ms | 9,8 ms | 114,7 ms | 0,00 % |
| Pic | 800 req/s | 239 233 | 797,4 req/s | 2,5 ms | 8,1 ms | 1 004,8 ms | 2 001,9 ms | 0,21 % |
| Récupération | 80 req/s | 23 963 | 79,9 req/s | 3,0 ms | 4,9 ms | 9,5 ms | 1 630,0 ms | 0,00 % |

| Phase | Flux | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: |
| Nominal | Réservations (`201`) | 5,6 ms | 7,8 ms | 10,9 ms | 114,7 ms |
| Nominal | Refus (`404`) | 2,4 ms | 4,2 ms | 6,7 ms | 76,5 ms |
| Pic | Réservations (`201`) | 2,9 ms | 10,2 ms | 1 012,6 ms | 2 001,9 ms |
| Pic | Refus (`404`) | 1,2 ms | 5,2 ms | 995,2 ms | 2 001,2 ms |
| Récupération | Réservations (`201`) | 3,6 ms | 5,6 ms | 11,1 ms | 1 630,0 ms |
| Récupération | Refus (`404`) | 1,7 ms | 3,1 ms | 6,8 ms | 1 623,5 ms |

**Échec, sur le p99 et les erreurs.** Le débit est presque tenu
(797,4 req/s) et le p95 du pic reste à 8,1 ms. Mais les 500 requêtes en
échec (délai de 2 s dépassé) et les 806 itérations abandonnées se concentrent
entre la 714e et la 721e seconde, juste avant la fin du pic. Ce blocage
d'environ sept secondes porte le p99 du pic à 1 s, pour les deux flux. Sa
cause n'est pas identifiée : les journaux de la campagne suivante ne
montrent que des points de contrôle PostgreSQL périodiques, étalés sur
4,5 minutes. Pistes à vérifier : contention sur la base, `autovacuum` de la
table `hospital` très mise à jour, pause de la JVM.

### Réservations réussies, pic à 400 req/s (exploratoire)

| Phase | Débit cible | Requêtes | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nominal | 80 req/s | 24 001 | 80,0 req/s | 3,5 ms | 5,2 ms | 8,2 ms | 100,7 ms | 0,00 % |
| Pic | 400 req/s | 120 001 | 400,0 req/s | 2,9 ms | 6,0 ms | 15,1 ms | 166,0 ms | 0,00 % |
| Récupération | 80 req/s | 24 001 | 80,0 req/s | 3,1 ms | 5,6 ms | 22,9 ms | 311,7 ms | 0,00 % |

**Réussi** : aucune itération abandonnée, toutes les vérifications conformes,
172 802 réservations en base pour 172 802 réponses `201`.

### Ressources pendant le pic

Moyenne et maximum des relevés `docker stats` (toutes les 5 s) ; 100 %
correspond à un cœur.

| Campagne | API (limite 200 %) | PostgreSQL (limite 200 %) | Redis (limite 100 %) | k6 (hôte) |
| --- | --- | --- | --- | --- |
| Réussies 800 req/s | 79 % (max 126 %), 959 Mio | **170 %** (max 220 %), 274 Mio | 4 % (max 62 %), 33 Mio | 63 % (max 179 %), 637 Mio |
| Mixte 800 req/s | 51 % (max 127 %), 519 Mio | 104 % (max 216 %), 235 Mio | 72 % (max 86 %), 48 Mio | 32 % (max 55 %), 219 Mio |
| Réussies 400 req/s | 30 % (max 45 %), 463 Mio | 83 % (max 130 %), 238 Mio | 79 % (max 85 %), 51 Mio | 20 % (max 35 %), 145 Mio |

Au pic à 800 réservations/s, PostgreSQL est à sa limite de 2 CPU, alors que
l'API n'en utilise qu'environ 40 % et que sa mémoire approche 1 Gio. Le
goulot d'étranglement observé est donc la base, qui traite environ huit
requêtes SQL par réservation : verrou d'idempotence, recherche PostGIS, mise
à jour du lit, relecture de l'hôpital et trois insertions, dont celle de
l'outbox qui déclenche une notification. Une campagne avec plus de CPU pour
PostgreSQL, ou avec un pool de connexions plus grand, permettrait de vérifier
si la limite vient du CPU ou de la sérialisation des validations. En mode
mixte, les refus ne coûtent qu'une recherche, ce qui explique la charge
moindre.

## (b) Santé de l'outbox

Retard de publication = `published_at - occurred_at`, par phase
d'occurrence de l'événement. « En attente » et « plus ancien » viennent des
relevés toutes les 5 s pendant la campagne.

| Campagne | Phase | Événements | En attente max | Plus ancien max | Retard p50 | Retard p95 | Retard p99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Réussies 800 | Nominal | 24 001 | 62 | 0,8 s | 4,3 ms | 328,2 ms | 883,5 ms |
| Réussies 800 | Pic | 123 568 | 120 300 | 294,5 s | 445,8 s | 997,9 s | 1 063,0 s |
| Réussies 800 | Récupération | 33 867 | 131 577 | 461,1 s | 1 247,8 s | 1 351,0 s | 1 358,8 s |
| Mixte 800 | Nominal | 12 001 | 1 | 0,0 s | 4,6 ms | 6,4 ms | 8,0 ms |
| Mixte 800 | Pic | 118 979 | 52 251 | 131,4 s | 100,6 s | 351,0 s | 384,6 s |
| Mixte 800 | Récupération | 12 633 | 53 904 | 323,6 s | 322,6 s | 395,2 s | 397,8 s |
| Réussies 400 | Nominal | 23 999 | 65 | 0,8 s | 4,6 ms | 9,2 ms | 790,2 ms |
| Réussies 400 | Pic | 119 945 | 57 558 | 143,9 s | 133,7 s | 402,9 s | 443,0 s |
| Réussies 400 | Récupération | 24 072 | 57 508 | 337,7 s | 463,9 s | 503,0 s | 508,1 s |

| Campagne | En attente à la fin de la récupération | Verdict (b) |
| --- | ---: | --- |
| Réussies 800 | 91 727 | Échec |
| Mixte 800 | 22 188 | Échec |
| Réussies 400 | 39 192 | Échec |

**Échec dans les trois campagnes.** Au régime nominal, chaque événement est
publié en quelques millisecondes. Au pic, le publieur n'atteint que 205 à
217 événements/s (mixte et 400 req/s), et environ 42/s seulement à
800 réservations/s, faute de ressources en base. Le retard grandit donc tout
au long du pic. Pendant la récupération, il publie 140 à 150 événements/s
(mixte, 400 req/s), et 170 à 270/s à 800 réservations/s, une fois la base
libérée. Comme 80 nouveaux événements arrivent chaque seconde, le retard
ne baisse que lentement.
Le retard se résorbe après la campagne, pendant les contrôles SQL : tout est
publié pour le mixte et pour 400 req/s, mais 16 407 événements restaient en
attente à la fin des contrôles de la campagne à 800 réservations/s. Les
percentiles de retard sont calculés à ce moment-là, sur les seuls événements
publiés.

Mesures Actuator du publieur, réservations réussies à 400 req/s :

| Phase | Événements publiés | Durée moyenne par événement | dont `XADD` Redis | Sélection SQL |
| --- | ---: | ---: | ---: | ---: |
| Nominal | 80,0/s | 2,18 ms | 1,64 ms | 0,11 ms |
| Pic | 205,9/s | 4,84 ms | 4,31 ms | 0,11 ms |
| Récupération | 141,8/s | 7,05 ms | 6,56 ms | 0,13 ms |

Le publieur traite les événements un par un, et l'envoi `XADD` occupe
l'essentiel de chaque publication. Sa durée augmente tout au long de la
campagne, alors que Redis approche sa limite d'un CPU. Après la campagne à
400 req/s, le stream contenait **172 802 entrées**, malgré `MAXLEN ~ 10000`.
Le publieur demande une rétention `ACKED`, qui ne supprime que les entrées
acquittées par tous les groupes. Or le groupe `reservation-consumers` n'a
aucun consommateur dans ce test : rien n'est acquitté, le stream ne peut pas
être réduit et grossit sans limite. L'hypothèse la plus probable est que
chaque `XADD` tente cette réduction sans y parvenir, et coûte de plus en plus
cher à mesure que le stream grandit. Elle reste à confirmer. En production,
un consommateur arrêté aurait le même effet, jusqu'à la limite mémoire de
Redis (256 Mo).

## Cohérence des données

| Campagne | Réservations en base | Réponses `201` reçues | Cohérence interne |
| --- | ---: | ---: | --- |
| Réussies 800 | 186 223 | 62 110 | Conforme |
| Mixte 800 | 146 004 | 145 760 | Conforme |
| Réussies 400 | 172 802 | 172 802 | Conforme |

Aucun lit négatif, aucune surréservation, une demande enregistrée et un
événement `BedReserved` conforme par réservation, y compris sous saturation.
Les écarts avec les réponses `201` correspondent aux requêtes abandonnées
par le client après 2 s puis validées par l'API. Dans un vrai client, une
relance avec la même clé `Idempotency-Key` retrouverait la réservation sans
en créer une seconde.

## Limites de la mesure

- **Générateur sur la même machine** : k6 tourne sur l'hôte et a consommé
  jusqu'à 1,8 cœur au pic. Il partage le processeur avec la VM Docker. Le
  trafic passe par la redirection de ports de Docker Desktop, qui ajoute une
  latence et un coût absents d'un déploiement réel.
- **Ressources partagées** : les limites fixent un plafond par conteneur, pas
  une réservation exclusive. macOS, Docker Desktop et k6 se partagent les
  10 cœurs (6,5 CPU au plus pour les conteneurs).
- **Une seule exécution par scénario** : la reproductibilité n'est pas
  établie. Une première campagne « réservations réussies » à 800 req/s,
  lancée sur `54f425a` puis écartée pour défaut d'outillage, présentait le
  même profil d'échec (96,5 % d'erreurs au pic).
- **Délai client de 2 s** : au-delà, la latence réelle n'est pas mesurée. Les
  percentiles plafonnent vers 2 000 ms.
- **Pas de consommateur Redis** : le comportement du stream (rétention)
  n'est pas celui d'un système avec un consommateur actif.
- **Bornes des phases** : calculées depuis le lancement de k6, à une seconde
  près, pour le retard de l'outbox et les ressources.
- **Champs `outbox.published` et `outbox.events` des `verdict.json`** : sur
  le commit mesuré, ils ont été relevés après les contrôles SQL, pendant
  lesquels le publieur continuait. La référence est `pending_at_end`, relevé
  juste après k6. Le commit `814f8d5`, postérieur aux mesures, relève les
  trois compteurs au même instant ; les valeurs mesurées ne changent pas.
- **Relevés Actuator** : pendant le pic à 800 réservations/s, 42 relevés ont
  dépassé 2 s. Le débit du publieur pendant ce pic est donc une moyenne sur
  un intervalle d'environ cinq minutes.
- `/actuator/info` ne contient pas le commit (le contexte Docker exclut
  `.git`). Le commit est relevé par le runner, et l'arbre était propre.

## Pistes

Ces pistes sortent du périmètre de ce lot, qui mesure la version actuelle :

1. Identifier la limite à 800 réservations/s : campagne avec plus de CPU
   pour PostgreSQL, puis analyse des attentes (`pg_stat_activity`,
   `pg_stat_statements`) et réglage du pool HikariCP.
2. Expliquer le blocage d'environ sept secondes en fin de pic du scénario
   mixte.
3. Revoir la rétention du stream : politique `ACKED` sans consommateur,
   coût de `XADD`.
4. Accélérer le publieur : envoi des `XADD` en pipeline ou par lots, une
   transaction par lot plutôt que par événement.
5. Répéter les campagnes, idéalement avec le générateur sur une autre machine.

## Fichiers

Les `summary.json` (k6) et `verdict.json` (environnement, verdicts,
ressources) de chaque exécution sont dans
[`resultats-2026-11-22/`](resultats-2026-11-22/) :

| Dossier | Exécution |
| --- | --- |
| `smoke-reussies/` | Smoke « réservations réussies » |
| `smoke-mixte/` | Smoke mixte |
| `reussies-pic-800/` | Réservations réussies, pic à 800 req/s |
| `mixte-pic-800/` | Mixte, pic à 800 req/s |
| `reussies-pic-400/` | Réservations réussies, pic à 400 req/s |

Les journaux et CSV complets (`k6.txt`, `outbox.csv`, `outbox-metrics.csv`,
`resources.csv`) sont volumineux et restent hors du dépôt.
