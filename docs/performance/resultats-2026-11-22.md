# Résultats des tests de charge du 22 novembre 2026

Campagnes du lot 6 (issue #24), sur la version actuelle : authentification
JWT Keycloak, PostgreSQL en TLS `verify-full`, Redis en TLS, outbox réveillée
par `LISTEN / NOTIFY`. Elles remplacent les campagnes du 28 octobre, antérieures
à ces évolutions. Méthode et critères : [stratégie](strategie-tests-charge.md) ;
commandes : [guide d'exécution](../../tests/performance/README.md).

Les améliorations de l'outbox qui ont suivi sont mesurées dans les
[résultats du 25 novembre](resultats-2026-11-25.md).

## Synthèse

Dans toutes les campagnes, l'API est limitée à 2 CPU et 1 Gio. Seule la
limite CPU de PostgreSQL change : 2 CPU dans la configuration de référence,
4 CPU dans la campagne complémentaire.

| Campagne | PostgreSQL | Pic | (a) Latence de l'API (pic) | (b) Outbox | Cohérence interne |
| --- | --- | --- | --- | --- | --- |
| Réservations réussies | 2 CPU (référence) | 800 req/s, 5 min | **Échec** : saturation, 95 % d'erreurs | **Échec** | Conforme |
| Mixte (50 % `201`, 50 % `404`) | 2 CPU (référence) | 800 req/s, 5 min | **Échec** : p95 8,1 ms mais p99 1 005 ms, 0,21 % d'erreurs | **Échec** | Conforme |
| Réservations réussies (exploratoire) | 2 CPU (référence) | 400 req/s, 5 min | **Réussi** : p95 6,0 ms, p99 15,1 ms | **Échec** | Conforme |
| Réservations réussies | **4 CPU** (complémentaire) | 800 req/s, 5 min | **Réussi** : p95 10,0 ms, p99 29,5 ms, 0 erreur | **Échec** | Conforme |
| Mixte | **4 CPU** (complémentaire) | 800 req/s, 5 min | **Réussi** : p95 4,3 ms, p99 8,6 ms, 0 erreur | **Échec** | Conforme |

**Verdict face à l'exigence** « moins de 200 ms jusqu'à 800 requêtes par
seconde, par instance » :

- **Configuration de référence (PostgreSQL à 2 CPU) : non satisfaite.** Le
  seuil est largement tenu à 80 req/s (p99 inférieur à 10 ms) et à
  400 réservations/s (p99 de 15 ms). À 800 réservations/s, PostgreSQL sature
  et le serveur n'en valide qu'environ 410 par seconde : la file d'attente
  s'allonge et les requêtes dépassent le délai client de 2 s. Avec
  400 réservations et 400 refus par seconde, la latence reste très basse,
  sauf pendant environ sept secondes en fin de pic, ce qui suffit à porter
  le p99 du pic à 1 s.
- **Base non limitante (PostgreSQL à 4 CPU) : satisfaite pour l'instance
  d'API.** La même instance d'API, toujours limitée à 2 CPU et 1 Gio, tient
  800 req/s pendant cinq minutes dans les deux scénarios, avec un p99 du pic
  de 29,5 ms (réservations réussies) et de 8,6 ms (mixte), sans erreur ni
  itération abandonnée. Elle utilise alors en moyenne 63 % de ses 200 % de
  CPU au pic. La limite constatée en référence vient donc de la base, pas de
  l'instance d'API. Ce résultat repose sur une exécution par scénario.

L'outbox (verdict b) ne suit pas un débit soutenu de 400 réservations/s, même
quand la base dispose de 4 CPU : elle
publie environ 140 à 240 événements/s, et son retard atteint plusieurs
minutes sans se résorber pendant les cinq minutes de récupération. Aucune
incohérence de données n'a été détectée : pas de surréservation, une demande
et un événement par réservation, même sous saturation.

## Environnement

| Élément | Valeur |
| --- | --- |
| Commit mesuré | `298a151ef81d140e9d6881515b2573493cccdc7e` (configuration de référence) et `9ce17f288b55c911b8c214ccea3f70e0ffba6554` (campagne complémentaire), arbre propre (`working_tree_clean: true` dans chaque `verdict.json`) |
| Code de l'API | Identique à `master` `576edf0` pour les deux commits (la branche ne modifie ni `src/`, ni `pom.xml`, ni `Dockerfile`), version `0.1.0-SNAPSHOT` |
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

## (a) Latence de l'API (référence, PostgreSQL à 2 CPU)

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

## (b) Santé de l'outbox (référence, PostgreSQL à 2 CPU)

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

## Campagne complémentaire : PostgreSQL à 4 CPU

**Objectif.** L'exigence porte sur une instance de service, l'API. En
configuration de référence, c'est PostgreSQL, limité à 2 CPU, qui sature.
Cette campagne vérifie si l'instance d'API, toujours limitée à 2 CPU et
1 Gio, tient le seuil à 800 req/s quand la base n'est plus le facteur
limitant.

**Seul changement** : `PERF_DATABASE_CPUS=4`, qui porte la limite CPU de
`database` à 4 (la valeur par défaut reste 2). Tout le reste est identique à
la référence : API à 2 CPU et 1 Gio (tas 75 %), Redis à 1 CPU, profil, données,
générateur, code de l'application. Commit mesuré `9ce17f2`, arbre propre,
`down -v` avant chaque campagne. Exécution : réservations réussies à 15:59
UTC, mixte à 16:59 UTC. Aucun autre conteneur détecté. Les deux
`verdict.json` enregistrent `database.cpus = 4.0` et `api.cpus = 2.0`.

Sur ce commit, les compteurs `pending_at_end`, `outbox.events` et
`outbox.published` sont relevés au même instant, juste après k6.

### (a) Latence de l'API : réussi dans les deux scénarios

Réservations réussies, pic à 800 req/s :

| Phase | Débit cible | Requêtes | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nominal | 80 req/s | 24 001 | 80,0 req/s | 3,4 ms | 4,7 ms | 6,6 ms | 46,6 ms | 0,00 % |
| Pic | 800 req/s | 240 001 | 800,0 req/s | 3,1 ms | 10,0 ms | 29,5 ms | 420,7 ms | 0,00 % |
| Récupération | 80 req/s | 24 000 | 80,0 req/s | 3,0 ms | 4,6 ms | 7,4 ms | 130,8 ms | 0,00 % |

Aucune itération abandonnée (440 utilisateurs virtuels au plus), toutes les
vérifications conformes, 292 802 réservations en base pour 292 802 réponses
`201`.

Mixte, pic à 800 req/s :

| Phase | Débit cible | Requêtes | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nominal | 80 req/s | 24 002 | 80,0 req/s | 3,7 ms | 6,8 ms | 8,4 ms | 49,6 ms | 0,00 % |
| Pic | 800 req/s | 240 001 | 800,0 req/s | 2,4 ms | 4,3 ms | 8,6 ms | 222,3 ms | 0,00 % |
| Récupération | 80 req/s | 24 002 | 80,0 req/s | 2,9 ms | 4,7 ms | 7,7 ms | 101,2 ms | 0,00 % |

| Phase | Flux | p50 | p95 | p99 | max |
| --- | --- | ---: | ---: | ---: | ---: |
| Pic | Réservations (`201`) | 2,6 ms | 5,0 ms | 10,9 ms | 222,3 ms |
| Pic | Refus (`404`) | 1,0 ms | 2,5 ms | 5,7 ms | 210,2 ms |
| Récupération | Réservations (`201`) | 3,2 ms | 5,2 ms | 9,3 ms | 101,2 ms |
| Récupération | Refus (`404`) | 1,5 ms | 3,0 ms | 5,3 ms | 62,2 ms |

Aucune itération abandonnée, 146 403 réservations en base pour
146 403 réponses `201`. Le blocage d'environ sept secondes observé en fin de
pic dans la configuration de référence ne se reproduit pas ici : la latence
maximale du pic est de 222 ms.

### Ressources pendant le pic

| Campagne | API (limite 200 %) | PostgreSQL (limite 400 %) | Redis (limite 100 %) | k6 (hôte) |
| --- | --- | --- | --- | --- |
| Réussies 800 req/s | 63 % (max 112 %), 553 Mio | 182 % (max 308 %), 329 Mio | 74 % (max 81 %), 87 Mio | 26 % (max 49 %), 215 Mio |
| Mixte 800 req/s | 45 % (max 66 %), 478 Mio | 98 % (max 115 %), 234 Mio | 77 % (max 86 %), 51 Mio | 25 % (max 46 %), 236 Mio |

Avec 4 CPU, PostgreSQL consomme en moyenne 182 % au pic des réservations
réussies, avec des pointes à 308 % : il dépasse donc régulièrement les 2 CPU
de la configuration de référence. L'API garde une marge (63 % sur 200 %).

### (b) Santé de l'outbox : échec dans les deux scénarios

| Campagne | Phase | Événements | En attente max | Plus ancien max | Retard p50 | Retard p95 | Retard p99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Réussies 800 | Nominal | 24 001 | 71 | 0,9 s | 4,4 ms | 157,1 ms | 850,1 ms |
| Réussies 800 | Pic | 239 882 | 180 636 | 225,8 s | 516,0 s | 2 494,6 s | 2 790,4 s |
| Réussies 800 | Récupération | 24 133 | 180 359 | 468,6 s | — | — | — |
| Mixte 800 | Nominal | 12 001 | 1 | 0,0 s | 4,6 ms | 6,3 ms | 7,6 ms |
| Mixte 800 | Pic | 119 938 | 46 706 | 116,8 s | 69,3 s | 305,5 s | 334,4 s |
| Mixte 800 | Récupération | 12 072 | 46 779 | 310,5 s | 256,5 s | 335,8 s | 342,8 s |

| Campagne | Publiés / événements juste après k6 | En attente à la fin de la récupération | Verdict (b) |
| --- | ---: | ---: | --- |
| Réussies 800 | 132 972 / 292 802 | 159 830 | Échec |
| Mixte 800 | 129 519 / 146 403 | 16 884 | Échec |

Le publieur atteint 196 événements/s au pic des réservations réussies et
242/s au pic du scénario mixte, puis 141 à 150/s pendant la récupération.
L'envoi `XADD` occupe toujours l'essentiel de chaque publication : 4,4 ms en
moyenne au pic des réservations réussies, 6,2 ms pendant la récupération. Les
4 CPU de PostgreSQL ne changent donc pas le débit du publieur, ce qui
conforte l'hypothèse d'une limite côté Redis (stream non réduit, rétention
`ACKED` sans consommateur). Dans les réservations réussies, aucun événement
de la récupération n'était encore publié quand le retard a été calculé, après
les contrôles SQL : 43 253 événements restaient alors en attente. Ces
contrôles ont duré environ 40 minutes, car le publieur continuait de vider
la file en parallèle.

## Cohérence des données

| Campagne | Réservations en base | Réponses `201` reçues | Cohérence interne |
| --- | ---: | ---: | --- |
| Réussies 800 | 186 223 | 62 110 | Conforme |
| Mixte 800 | 146 004 | 145 760 | Conforme |
| Réussies 400 | 172 802 | 172 802 | Conforme |
| Réussies 800, PostgreSQL 4 CPU | 292 802 | 292 802 | Conforme |
| Mixte 800, PostgreSQL 4 CPU | 146 403 | 146 403 | Conforme |

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

1. Dimensionner la base pour 800 réservations/s : la campagne à 4 CPU montre
   que l'API tient la charge quand PostgreSQL dispose de plus de 2 CPU ; une
   analyse des attentes (`pg_stat_activity`, `pg_stat_statements`) préciserait
   le besoin réel.
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
| `postgres-4cpu-reussies-pic-800/` | Complémentaire, PostgreSQL à 4 CPU : réservations réussies, pic à 800 req/s |
| `postgres-4cpu-mixte-pic-800/` | Complémentaire, PostgreSQL à 4 CPU : mixte, pic à 800 req/s |

Les journaux et CSV complets (`k6.txt`, `outbox.csv`, `outbox-metrics.csv`,
`resources.csv`) sont volumineux et restent hors du dépôt.
