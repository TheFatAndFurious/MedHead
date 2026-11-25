# Résultats des tests de charge du 25 novembre 2026

Campagnes de l'issue #6 : mesurer l'effet des deux premières améliorations de
l'outbox (groupes Redis créés une seule fois, consommateur de démonstration
qui acquitte les événements), puis expliquer la dégradation de la latence de
l'API qu'elles ont fait apparaître. Elles complètent les
[campagnes du 22 novembre](resultats-2026-11-22.md). Méthode et critères :
[stratégie](strategie-tests-charge.md) ; commandes :
[guide d'exécution](../../tests/performance/README.md).

## Synthèse

Toutes les campagnes utilisent le scénario « réservations réussies » avec un
pic à 400 req/s, la configuration de référence (API à 2 CPU, PostgreSQL à
2 CPU) et le consommateur `event-consumer`.

| Campagne | Synchronisation disque | (a) Latence de l'API (pic) | (b) Outbox | Publieur au pic |
| --- | --- | --- | --- | ---: |
| Groupes créés une fois | Par défaut | **Échec** : p95 399 ms, p99 791 ms, 218 itérations abandonnées | **Échec** : 26 événements en attente à la fin | 308 évén./s |
| Référence instrumentée | Par défaut | **Échec** : p95 367 ms, p99 854 ms, 337 itérations abandonnées | Réussi | 277 évén./s |
| Variante PostgreSQL | `synchronous_commit=off` | **Échec** : p95 6,6 ms mais p99 326 ms, 63 abandonnées | Réussi | 396 évén./s |
| Variante Redis | `appendfsync everysec` | **Échec** : p95 8,0 ms, p99 153 ms, 6 abandonnées | Réussi | 398 évén./s |
| Les deux réglages | Les deux | **Réussi** : p95 5,3 ms, p99 16,0 ms, 0 abandonnée | Réussi | 398 évén./s |

Le 22 novembre, sans ces améliorations, la même campagne tenait la latence
(p95 6,0 ms, p99 15,1 ms) mais le publieur plafonnait à 206 événements/s.

**Conclusions :**

1. **Le publieur n'est plus le facteur limitant.** Dès que la synchronisation
   disque ne ralentit plus Redis ou PostgreSQL, il suit les 400 événements/s
   du pic, et l'arriéré se résorbe en moins de 3 s après le pic, contre plus
   de 3 minutes en configuration par défaut. Le stream reste borné à
   10 000 entrées grâce aux acquittements du consommateur.
2. **La dégradation de l'API vient du disque, transmise par le verrou du
   `NOTIFY`.** Redis, avec `appendfsync always`, synchronise son fichier AOF
   à chaque `XADD`. Le publieur étant plus rapide, Redis synchronise plus
   souvent, sur le même disque virtuel que PostgreSQL. La synchronisation du
   journal PostgreSQL ralentit (0,15 ms en moyenne sans les synchronisations
   de Redis, 0,60 ms avec). Or, au `COMMIT` de toute transaction qui a émis
   un `NOTIFY`, PostgreSQL prend un verrou exclusif global et le garde
   jusqu'à la fin de cette synchronisation. Les commits des réservations passent donc
   un par un, et la file d'attente se reporte sur l'API.
3. **Retirer l'un ou l'autre maillon suffit presque.** L'attente
   `Lock:object` disparaît dans les deux variantes, et seule la combinaison
   des deux réglages passe tous les seuils.

Ces deux réglages acceptent de perdre des données confirmées en cas de panne
(voir [Compromis](#compromis-des-réglages)). Ils servent ici au diagnostic et
ne sont pas adoptés.

## Environnement

| Élément | Valeur |
| --- | --- |
| Commits mesurés | `b911f566b24606d35c036bca71a43d11e54b3cd4` (groupes créés une fois), `2302e103a901f6fea3a56cb33c7eb53b6a96cc4c` (référence instrumentée) et `2bce59ec2240a23efd9312cf9a921109b2e8060d` (variantes), arbre propre dans chaque `verdict.json` |
| Code de l'API et du consommateur | Identique pour les trois commits : les deux derniers ne modifient que l'outillage de mesure (`tests/performance/`, Compose de performance, script de démarrage de Redis) |
| Hôte | Apple M4, 10 cœurs, 16 Gio de RAM, macOS 15.8.1 |
| Docker | Docker Desktop, moteur 28.1.1, Compose 2.35.1 ; VM : 10 CPU, 15,6 Gio, disque `vda` stocké dans un fichier du SSD de l'hôte |
| Générateur | k6 v1.2.1, sur l'hôte, sans limite |
| Projet Compose | `medhead-lot6` |

Limites des conteneurs : celles du 22 novembre (API 2 CPU et 1 Gio,
PostgreSQL 2 CPU et 2 Gio, Redis 1 CPU et 512 Mio, Keycloak 1 CPU et 1 Gio,
base de Keycloak 0,5 CPU et 256 Mio), plus `event-consumer` à 0,5 CPU et
128 Mio. Profil : échauffement de 2 min, nominal à 80 req/s, pic à
400 req/s et récupération à 80 req/s, 5 min chacun.

Ordre d'exécution (heures UTC) : groupes créés une fois (09:46), référence
instrumentée (10:47), variante PostgreSQL (11:38), variante Redis (12:09),
les deux réglages (12:40).

## (a) Latence de l'API

| Campagne | Phase | Débit réel | p50 | p95 | p99 | max | Erreurs |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Groupes créés une fois | Nominal | 80,0 req/s | 3,5 ms | 5,8 ms | 20,2 ms | 408,0 ms | 0 % |
| Groupes créés une fois | **Pic** | 399,4 req/s | 3,2 ms | **399,0 ms** | **790,7 ms** | 1 781,3 ms | 0 % |
| Groupes créés une fois | Récupération | 79,9 req/s | 3,5 ms | 12,4 ms | 239,2 ms | 882,4 ms | 0 % |
| Référence instrumentée | Nominal | 80,0 req/s | 3,5 ms | 6,3 ms | 19,4 ms | 244,8 ms | 0 % |
| Référence instrumentée | **Pic** | 399,1 req/s | 5,5 ms | **366,8 ms** | **854,1 ms** | 2 001,0 ms | 0,03 % |
| Référence instrumentée | Récupération | 79,8 req/s | 5,5 ms | 75,0 ms | 779,1 ms | 1 926,1 ms | 0 % |
| Variante PostgreSQL | Nominal | 80,0 req/s | 3,5 ms | 5,1 ms | 9,2 ms | 61,0 ms | 0 % |
| Variante PostgreSQL | **Pic** | 399,8 req/s | 2,6 ms | 6,6 ms | **325,6 ms** | 1 755,8 ms | 0 % |
| Variante PostgreSQL | Récupération | 80,0 req/s | 3,6 ms | 4,9 ms | 8,1 ms | 31,9 ms | 0 % |
| Variante Redis | Nominal | 80,0 req/s | 4,0 ms | 6,1 ms | 11,5 ms | 160,8 ms | 0 % |
| Variante Redis | **Pic** | 400,0 req/s | 2,5 ms | 8,0 ms | 152,6 ms | 1 248,8 ms | 0 % |
| Variante Redis | Récupération | 80,0 req/s | 4,1 ms | 6,6 ms | 18,5 ms | 653,2 ms | 0 % |
| Les deux réglages | Nominal | 80,0 req/s | 4,0 ms | 6,4 ms | 11,8 ms | 62,5 ms | 0 % |
| Les deux réglages | **Pic** | 400,0 req/s | 2,5 ms | **5,3 ms** | **16,0 ms** | 130,1 ms | 0 % |
| Les deux réglages | Récupération | 80,0 req/s | 4,0 ms | 5,2 ms | 7,6 ms | 245,4 ms | 0 % |

Les 33 erreurs de la référence instrumentée sont des dépassements du délai
client de 2 s : les réservations correspondantes ont bien été validées
(172 464 en base pour 172 431 réponses `201`). Dans les autres campagnes, le
nombre de réservations en base est égal au nombre de réponses `201`. La
cohérence interne est conforme dans toutes les campagnes.

La variante Redis échoue seulement sur ses 6 itérations abandonnées ; son
p99 reste sous 200 ms.

## (b) Santé de l'outbox

| Campagne | Phase | En attente max | Plus ancien max | Retard p50 | Retard p95 | Retard p99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Groupes créés une fois | Pic | 26 909 | 67,3 s | 15,5 s | 80,1 s | 81,2 s |
| Groupes créés une fois | Récupération | 26 279 | 81,3 s | 3,8 ms | 71,4 s | 79,3 s |
| Référence instrumentée | Pic | 36 617 | 92,1 s | 36,0 s | 123,0 s | 125,7 s |
| Référence instrumentée | Récupération | 35 530 | 125,5 s | 30,9 s | 119,3 s | 124,5 s |
| Variante PostgreSQL | Pic | 825 | 2,3 s | 510,5 ms | 995,9 ms | 2 392,6 ms |
| Variante PostgreSQL | Récupération | 47 | 0,6 s | 3,1 ms | 7,2 ms | 814,5 ms |
| Variante Redis | Pic | 748 | 2,1 s | 518,3 ms | 981,9 ms | 2 225,5 ms |
| Variante Redis | Récupération | 11 | 0,1 s | 2,9 ms | 50,8 ms | 787,9 ms |
| Les deux réglages | Pic | 397 | 1,0 s | 497,6 ms | 956,9 ms | 996,7 ms |
| Les deux réglages | Récupération | 58 | 0,7 s | 2,8 ms | 3,6 ms | 510,7 ms |

| Campagne | En attente à la fin | Arriéré résorbé après le pic | Verdict (b) |
| --- | ---: | ---: | --- |
| Groupes créés une fois | 26 | 108,5 s | Échec |
| Référence instrumentée | 0 | 201,7 s | Réussi |
| Variante PostgreSQL | 0 | 2,2 s | Réussi |
| Variante Redis | 0 | 2,6 s | Réussi |
| Les deux réglages | 0 | 2,5 s | Réussi |

« Arriéré résorbé » : délai entre la fin du pic et le premier relevé où le
plus ancien événement en attente a moins d'une seconde.

Mesures Actuator du publieur au pic :

| Campagne | Événements publiés | Durée moyenne par événement | dont `XADD` | Sélection SQL |
| --- | ---: | ---: | ---: | ---: |
| 22 novembre, avant les améliorations | 205,9/s | 4,84 ms | 4,31 ms | 0,11 ms |
| Groupes créés une fois | 308,1/s | 2,83 ms | 1,58 ms | 0,11 ms |
| Référence instrumentée | 277,1/s | 3,22 ms | 1,73 ms | 0,21 ms |
| Variante PostgreSQL | 396,2/s | 0,86 ms | 0,57 ms | 0,11 ms |
| Variante Redis | 397,6/s | 0,53 ms | 0,12 ms | 0,11 ms |
| Les deux réglages | 397,9/s | 0,41 ms | 0,14 ms | 0,12 ms |

Le CPU moyen de Redis au pic passe de 78,5 % (22 novembre, stream de
172 802 entrées) à 4,6 % (groupes créés une fois). Après cette campagne, le
stream contenait 10 000 entrées, sans retard du consommateur : la rétention
`ACKED` fonctionne dès que les événements sont acquittés.

Dans les trois variantes, le retard au pic est réparti presque uniformément
entre 0 et 1 s (p50 vers 500 ms, p99 vers 1 s), alors que chaque publication
dure moins d'une milliseconde et que le publieur suit le débit. La cause de
ce retard d'environ une seconde n'est pas encore identifiée.

## Entrées-sorties disque au pic

Relevés décrits dans le [guide d'exécution](../../tests/performance/README.md#entrées-sorties-disque).
La première campagne n'était pas instrumentée.

| Mesure | Référence instrumentée | Variante PostgreSQL | Variante Redis | Les deux réglages |
| --- | ---: | ---: | ---: | ---: |
| Synchronisations du journal PostgreSQL | 668/s | 5/s | 787/s | 5/s |
| Durée moyenne d'une synchronisation PostgreSQL | **0,60 ms** | 0,90 ms | **0,15 ms** | 0,68 ms |
| Synchronisation AOF Redis la plus lente de chaque seconde : p50 / p95 / max | 9 / 64 / 1 494 ms | 2 / 15 / 244 ms | aucune | aucune |
| Pression d'entrées-sorties de la VM | 10,0 % | 2,6 % | 1,0 % | 0,1 % |
| Occupation du disque `vda` | 58,6 % | 23,4 % | 9,2 % | 0,6 % |
| Écritures sur `vda` | 3 571/s | 2 445/s | 1 586/s | 20/s |
| Remplissage de la file des `NOTIFY` (max) | non relevé | 0 | 0 | 0 |

Sessions PostgreSQL actives en moyenne au pic, par événement d'attente
(600 instantanés) :

| Attente | Référence instrumentée | Variante PostgreSQL | Variante Redis | Les deux réglages |
| --- | ---: | ---: | ---: | ---: |
| `CPU` (aucune attente) | 1,11 | 0,67 | 0,61 | 0,56 |
| **`Lock:object`** (verrou du `NOTIFY` au `COMMIT`) | **1,02** | 0 | 0,07 | 0 |
| `IO:WalSync` | 0,41 | 0 | 0,10 | 0 |
| `LWLock:WALWrite` | 0,09 | 0 | 0,01 | 0 |
| Total des sessions actives | 2,72 | 0,70 | 0,86 | 0,60 |

Lecture :

- Dans la **variante Redis**, PostgreSQL synchronise toujours son journal à
  chaque commit, et même plus souvent qu'en référence, mais chaque
  synchronisation est quatre fois plus rapide. Le ralentissement de
  PostgreSQL venait donc des synchronisations de Redis sur le disque partagé.
  L'attente `Lock:object` résiduelle apparaît par à-coups, aux mêmes moments
  que des pics de `IO:WalSync`.
- Dans la **variante PostgreSQL**, le verrou du `NOTIFY` ne dure plus que le
  temps d'écrire en mémoire : l'attente `Lock:object` disparaît. Le p99 du
  pic reste à 326 ms sans qu'aucune attente PostgreSQL l'explique. Les
  synchronisations de Redis, qui atteignent encore 244 ms, en sont la cause
  probable, non démontrée.
- La file des `NOTIFY` reste vide : les notifications ne s'accumulent pas.

## Compromis des réglages

| Réglage | Gain mesuré | Ce qui est perdu en cas d'arrêt brutal |
| --- | --- | --- |
| PostgreSQL `synchronous_commit=off` | Plus d'attente du verrou du `NOTIFY` ; p95 de l'API à 6,6 ms | Les transactions validées depuis moins de 600 ms environ, **réservations comprises**, alors que l'API a déjà répondu `201`. Un événement déjà envoyé à Redis peut annoncer une réservation perdue. Pas de corruption |
| Redis `appendfsync everysec` | Synchronisations PostgreSQL 4 fois plus rapides ; `XADD` de 1,73 à 0,12 ms | Environ une seconde d'événements, déjà marqués publiés dans l'outbox et donc jamais renvoyés |

Pistes qui ne perdent pas de réservations, non mesurées :

- `synchronous_commit=off` limité aux transactions du publieur (`SET LOCAL`) :
  au pire, un événement est renvoyé, ce que la livraison au moins une fois
  prévoit déjà ;
- réveiller le publieur sans `NOTIFY` dans la transaction de réservation, ce
  qui supprime le verrou ;
- séparer les volumes de PostgreSQL et de Redis dans un déploiement réel.

## Limites de la mesure

- **Une exécution par variante** : les ordres de grandeur sont nets, mais
  les petits écarts (6 ou 63 itérations abandonnées) ne sont pas établis.
- **Disque virtuel partagé** : sous Docker Desktop, tous les conteneurs
  écrivent dans le même fichier de l'hôte. La contention observée dépend de
  cette virtualisation ; sur des volumes séparés, elle serait différente. Le
  verrou du `NOTIFY`, en revanche, ferait passer les commits un par un sur
  tout disque lent.
- **Générateur sur la même machine**, comme le 22 novembre.
- **Compteurs de vidage de `vda`** : leurs durées moyennes (de 13 ms à
  1,5 s) ne sont pas cohérentes d'une campagne à l'autre et ne sont pas
  interprétées.
- **Instantanés d'attente** : 600 par phase, ils donnent des proportions,
  pas des durées. Les sessions de l'API et du publieur ne sont pas
  distinguées.

## Fichiers

Les `summary.json` (k6) et `verdict.json` (environnement, verdicts,
ressources, bloc `io` pour les campagnes instrumentées) de chaque exécution
sont dans [`resultats-2026-11-25/`](resultats-2026-11-25/) :

| Dossier | Exécution |
| --- | --- |
| `reussies-pic-400-groupes-une-fois/` | Groupes créés une fois, consommateur de démonstration (`b911f56`) |
| `reussies-pic-400-reference/` | Même code, relevés disque (`2302e10`) |
| `variante-postgres-sans-synchro/` | `PERF_SYNCHRONOUS_COMMIT=off` (`2bce59e`) |
| `variante-redis-everysec/` | `PERF_REDIS_APPENDFSYNC=everysec` (`2bce59e`) |
| `variante-deux-reglages/` | Les deux réglages (`2bce59e`) |

Les journaux et CSV complets (`k6.txt`, `outbox.csv`, `outbox-metrics.csv`,
`resources.csv`, `io.csv`, `pg-waits.csv`, `redis-latency.csv`) restent hors
du dépôt.
