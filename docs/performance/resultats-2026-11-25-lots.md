# Résultats des tests de charge du 25 novembre 2026 : lots et réveil en mémoire

Deux campagnes complémentaires aux [campagnes du 25 novembre](resultats-2026-11-25.md),
qui avaient montré que la latence de l'API au pic dépend de la vitesse du disque :
le `NOTIFY` émis dans la transaction de réservation fait passer les commits un par
un. Elles mesurent :

1. la **publication par lots** (#40) : une transaction et un pipeline `XADD` par
   lot, et la suppression de l'attente de 1 s après un lot incomplet ;
2. le **réveil en mémoire** (#41), ajouté aux lots : plus de `NOTIFY` dans la
   transaction de réservation, réveil du publieur après le commit et scrutation de
   secours toutes les secondes.

Méthode et critères : [stratégie](strategie-tests-charge.md) ; commandes :
[guide d'exécution](../../tests/performance/README.md).

## Synthèse

Scénario « réservations réussies », pic à 400 req/s, configuration de référence
(API à 2 CPU, PostgreSQL à 2 CPU), consommateur `event-consumer`, réglages de
synchronisation disque par défaut (`synchronous_commit=on`,
`appendfsync always`).

| Campagne | (a) Latence de l'API (pic) | (b) Outbox | Attente `Lock:object` |
| --- | --- | --- | ---: |
| Référence instrumentée (25 novembre) | **Échec** : p95 367 ms, p99 854 ms, 337 itérations abandonnées | Réussi, arriéré résorbé en 202 s | 1,02 |
| Lots (#40) | **Échec** : p95 334 ms, p99 616 ms, 151 abandonnées | Réussi, 2,2 s | 0,95 |
| Lots et réveil en mémoire (#41) | **Échec de justesse** : p95 18 ms, p99 213 ms, 71 abandonnées | Réussi, 3,1 s | **0** |

**Conclusions :**

1. **Les lots règlent le publieur, pas l'API.** Le publieur suit la charge
   (arriéré maximal de 594 événements au lieu de 36 617) et le retard d'environ
   1 s disparaît. Mais une session attend toujours le verrou du `NOTIFY` : Redis
   synchronise encore son fichier AOF environ 74 fois par seconde, chaque fois en
   10 ms environ, et le disque reste occupé à 59 %.
2. **Retirer le `NOTIFY` supprime l'attente du verrou.** Le p95 de l'API au pic
   passe de 334 à 18 ms, et le p99 de 616 à 213 ms. Les phases nominale et de
   récupération ont un p99 de 8 ms.
3. **Le reste du p99 vient du disque, sans verrou.** Chaque réservation attend
   l'écriture de son propre journal (`IO:WalSync` et `LWLock:WALWrite`, 0,19
   session chacun), qui reste lente par moments sur le disque partagé avec Redis
   (synchronisation Redis p95 64 ms, maximum 696 ms).
4. **Réveillé à chaque réservation, le publieur fait des lots minuscules** : 1,3
   événement en moyenne, soit 256 pipelines Redis et 256 commits par seconde.
   Regrouper les événements pendant quelques dizaines de millisecondes réduirait
   ces synchronisations ; ce n'est pas mesuré.

## Environnement

| Élément | Valeur |
| --- | --- |
| Lots (#40) | Commit `cd0ae9e2250a92b0542c5d08768e9a963f3c92cb` : fusion locale, non publiée, de `perf/40-outbox-batch` (`c273e99ebb29ce163a2766a5b3a7c98f1d72df05`) et de l'outillage de mesure `test/6-io-instrumentation` (`2bce59ec2240a23efd9312cf9a921109b2e8060d`) |
| Lots et réveil en mémoire (#41) | Commit `c685d4210406ad3dff3f52777bb481e39872bc95` : fusion locale, non publiée, de `perf/41-outbox-wake-in-memory` (`052f874e4dfccd6126e7dadc33d05ab1a16d8ba3`) et du même outillage |
| Arbre | Propre dans les deux `verdict.json` ; le code de l'API est celui des branches citées, l'outillage ne modifie que `tests/performance/`, le Compose de performance et le démarrage de Redis |
| Hôte, Docker, générateur, limites des conteneurs | Identiques aux [campagnes du 25 novembre](resultats-2026-11-25.md#environnement) |

Ordre d'exécution (heures UTC) : lots (13:58), lots et réveil en mémoire (15:11).

## (a) Latence de l'API

| Campagne | Phase | p50 | p95 | p99 | max | Erreurs |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Lots | Nominal | 3,2 ms | 5,5 ms | 15,9 ms | 305 ms | 0 % |
| Lots | **Pic** | 3,6 ms | **334,3 ms** | **615,7 ms** | 2 000 ms | 0,002 % |
| Lots | Récupération | 3,2 ms | 6,4 ms | 20,6 ms | 458 ms | 0 % |
| Lots et réveil | Nominal | 3,0 ms | 4,8 ms | 8,4 ms | 148 ms | 0 % |
| Lots et réveil | **Pic** | 2,9 ms | **18,0 ms** | **212,7 ms** | 1 498 ms | 0 % |
| Lots et réveil | Récupération | 3,0 ms | 4,8 ms | 7,8 ms | 49 ms | 0 % |

Itérations abandonnées : 151 (lots), 71 (lots et réveil). Les deux campagnes
échouent au verdict (a) ; la seconde seulement sur le p99 du pic et sur ces
itérations abandonnées.

Cohérence interne conforme dans les deux campagnes. Lots : 172 648 réservations
en base pour 172 646 réponses `201`, l'écart correspondant à des requêtes dont
le client a abandonné l'attente après 2 s. Lots et réveil : 172 729 pour
172 729.

## (b) Santé de l'outbox

| Campagne | Phase | En attente max | Plus ancien max | Retard p50 | Retard p95 | Retard p99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Lots | Nominal | 3 | 0,02 s | 4,6 ms | 6,7 ms | 16,9 ms |
| Lots | Pic | 594 | 2,2 s | 29,7 ms | 906,1 ms | 1 288,2 ms |
| Lots | Récupération | 57 | 0,7 s | 4,6 ms | 8,2 ms | 293,9 ms |
| Lots et réveil | Nominal | 1 | 0,00 s | 2,5 ms | 4,0 ms | 7,4 ms |
| Lots et réveil | Pic | 540 | 1,4 s | 4,3 ms | 791,7 ms | 1 212,7 ms |
| Lots et réveil | Récupération | 1 | 0,01 s | 2,4 ms | 4,2 ms | 7,9 ms |

Aucun événement en attente à la fin ; arriéré résorbé 2,2 s et 3,1 s après le pic.

Mesures Actuator du publieur au pic :

| Campagne | Lots/s | Événements par lot | Pipeline `XADD` (moyenne, max) | Passage complet (moyenne) | Gap max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Référence (un événement par transaction) | 277 transactions | 1 | 1,7 ms par `XADD` | 3,2 ms | 1,003 s |
| Lots | 73,7 | 5,4 | 9,2 ms, 1,07 s | 7,9 ms | 0,94 s |
| Lots et réveil | 256,3 | 1,3 | 3,1 ms, 1,29 s | 3,3 ms | 0,12 s |

Le gap maximal égal au délai de nouvelle tentative (1,003 s) dans la référence
signalait l'attente supprimée par #40. Avec le réveil en mémoire, le publieur
ne reste jamais plus de 0,12 s sans passage pendant le pic. Les pipelines les
plus lents (plus d'une seconde) sont du même ordre que les synchronisations
Redis les plus longues, ce qui expliquerait le retard p95 de près d'une
seconde ; la corrélation dans le temps n'a pas été vérifiée.

## Entrées-sorties disque et attentes au pic

| Mesure | Référence | Lots | Lots et réveil |
| --- | ---: | ---: | ---: |
| Synchronisations du journal PostgreSQL | 668/s | 469/s | 622/s |
| Durée moyenne d'une synchronisation PostgreSQL | 0,60 ms | 0,74 ms | 0,40 ms |
| Synchronisation AOF Redis la plus lente de chaque seconde : p50 / p95 / max | 9 / 64 / 1 494 ms | 10 / 83 / 877 ms | 6 / 64 / 696 ms |
| Écritures du conteneur Redis | 811/s | 667/s | 796/s |
| Pression d'entrées-sorties de la VM | 10,0 % | 10,5 % | 7,6 % |
| Occupation du disque `vda` | 58,6 % | 59,0 % | 44,3 % |

| Sessions actives en moyenne, par attente | Référence | Lots | Lots et réveil |
| --- | ---: | ---: | ---: |
| `CPU` (aucune attente) | 1,11 | 0,83 | 0,56 |
| **`Lock:object`** | **1,02** | **0,95** | **0** |
| `IO:WalSync` | 0,41 | 0,36 | 0,19 |
| `LWLock:WALWrite` | 0,09 | 0,03 | 0,19 |
| Total | 2,72 | 2,28 | 1,01 |

CPU moyen au pic : API 45 % puis 60 % (sur 200 %), PostgreSQL 89 % puis 83 %,
Redis 11 % puis 24 %. La hausse de Redis suit le nombre de pipelines.

## Limites de la mesure

- **Une exécution par campagne.**
- **Commits mesurés non publiés** : les deux campagnes ont tourné sur des fusions
  locales du code et de l'outillage de mesure, pour obtenir les mêmes relevés que
  le 25 novembre. Le code de l'API est identique à celui des branches citées.
- **Disque virtuel partagé** : comme le 25 novembre, tous les conteneurs écrivent
  dans le même fichier de l'hôte sous Docker Desktop. Le reste du p99 en dépend
  directement.
- **Générateur sur la même machine.**

## Pistes

1. Regrouper les événements au réveil (attendre quelques dizaines de
   millisecondes avant un lot incomplet) pour réduire les synchronisations du
   publieur, puis mesurer.
2. Répéter la campagne sur des volumes séparés pour PostgreSQL et Redis, plus
   proches d'un déploiement réel.

## Fichiers

Les `summary.json` et `verdict.json` sont dans
[`resultats-2026-11-25-lots/`](resultats-2026-11-25-lots/) :

| Dossier | Exécution |
| --- | --- |
| `lots-pic-400/` | Lots (#40) |
| `lots-reveil-memoire-pic-400/` | Lots et réveil en mémoire (#41) |
