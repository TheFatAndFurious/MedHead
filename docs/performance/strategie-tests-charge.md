# Stratégie de tests de charge du POC MedHead

## Objectif et outil retenu

Vérifier que l'API de réservation supporte une charge nominale de **80 requêtes
par seconde** et un **pic de 800 requêtes par seconde**, avec un **p95 strictement
inférieur à 200 ms**. Le pic ne représente pas une charge soutenue permanente.

L'outil retenu est **Grafana k6** : scénarios HTTP versionnés dans le dépôt,
débit d'arrivée imposé et critères de réussite automatisables. Les tests
exercent l'API réelle, PostgreSQL/PostGIS et le publieur outbox vers Redis.
Ils ne remplacent pas les tests unitaires et d'intégration existants.

## Périmètre et décisions

- Uniquement `POST /api/v1/hospitals/reservations`, avec des coordonnées et une
  spécialité valides.
- `201` : réservation créée ; `404` : aucun lit disponible pour la spécialité.
  Ces deux réponses sont des résultats métier attendus selon le scénario.
- `400`, `5xx`, délais dépassés et erreurs de transport sont des échecs.
- Aucun appel de recherche séparé, aucune relance automatique des requêtes.
  Chaque nouvel appel peut créer une nouvelle réservation.
- Les durées ci-dessous et la répartition 50/50 du scénario mixte sont des
  hypothèses de campagne proposées, à confirmer lors de l'implémentation.

## Profil de charge commun

| Phase | Débit d'arrivée | Durée proposée | Nombre de requêtes prévu |
| --- | --- | --- | --- |
| Échauffement | Rampe linéaire de 0 à 80 req/s | 2 min | Environ 4 800 |
| Nominal | 80 req/s | 5 min | 24 000 |
| Pic | Passage immédiat à 800 req/s | 1 min | 48 000 |
| Récupération | Retour immédiat à 80 req/s | 5 min | 24 000 |

La campagne dure 13 minutes et prévoit environ **100 800 requêtes**.
L'échauffement est exclu des critères de latence. Les trois autres phases
sont évaluées séparément ; une bonne latence nominale ne doit pas masquer
un dépassement pendant le pic.

Utiliser les exécuteurs k6 à débit d'arrivée, et non un nombre fixe
d'utilisateurs virtuels. Dimensionner les utilisateurs virtuels pour tenir
le débit même si les réponses ralentissent. Surveiller `dropped_iterations`
et les ressources du générateur : une campagne qui n'atteint pas la charge
prévue ne valide pas l'objectif.

## Scénarios et préparation des données

### A — Capacité suffisante

Toutes les demandes doivent obtenir un `201`. Préparer plus de 100 800 lits
disponibles, avec une marge proposée de 20 %, soit au moins **121 000 lits**
accessibles aux spécialités et aux coordonnées utilisées. Le total global
ne suffit pas : vérifier la capacité de chaque groupe de demandes et les
capacités partagées entre spécialités.

Répartir les coordonnées de manière déterministe sur plusieurs zones et
plusieurs hôpitaux. Conserver les mêmes données et la même répartition entre
exécutions pour comparer les versions. Un scénario de contention sur un seul
hôpital pourra faire l'objet d'un test distinct ultérieurement.

### B — Disponibilité mixte

Proposition initiale : 50 % des demandes doivent obtenir un `201`, 50 % un
`404`, à chaque phase. Maintenir deux flux de débit distincts pour maîtriser
la répartition, plutôt que laisser l'épuisement des lits la déterminer.

- Flux avec disponibilité : spécialités connues avec capacité suffisante
  pour environ 50 400 réservations, soit au moins **60 500 lits** avec marge.
- Flux sans disponibilité : spécialités connues sans aucun lit disponible
  dans tous les hôpitaux qui leur sont associés.

La recherche actuelle n'a pas de rayon maximal : des coordonnées éloignées
ou un hôpital local complet ne suffisent pas à provoquer un `404`. Une
spécialité inconnue produit un `400` et ne convient pas à ce scénario.

### État initial reproductible

Utiliser un environnement dédié avec des données synthétiques. Le jeu actuel
de 2 000 hôpitaux constitue une base, mais sa disponibilité initiale est
insuffisante pour ces campagnes. Rejouer `seed-load.sql` conserve les lits
restants : cette opération seule ne réinitialise pas le test.

Avant chaque scénario, préparer un état connu des hôpitaux, disponibilités,
réservations, événements outbox et streams Redis. Exclure les données de
démonstration pouvant influencer la sélection, particulièrement les `404`.
Ne pas supprimer de données dans un environnement partagé et ne pas
réapprovisionner les lits pendant la mesure.

## Critères de réussite

| Mesure | Critère |
| --- | --- |
| Débit | Débit prévu effectivement généré dans chaque phase ; aucune itération abandonnée |
| Latence HTTP | p95 < 200 ms, par phase mesurée, globalement et séparément pour chaque résultat métier attendu |
| Réponses | Aucun statut inattendu, échec réseau ou dépassement du délai client |
| Scénario A | Toutes les demandes aboutissent à un `201` |
| Scénario B | Chaque flux retourne le statut métier prévu |
| Cohérence | Aucun lit négatif, aucune surréservation ; une réservation et un événement outbox par `201` |

Mesurer le temps de requête HTTP k6 `http_req_duration`, pas le temps complet
d'une itération ni le délai de publication Redis. Rapporter également p50,
p99 et maximum pour rendre visibles les réponses au-delà du p95. Vérifier
les champs essentiels des réponses `201`, dont l'identifiant de réservation.
Configurer explicitement les statuts attendus dans k6 : un `404` prévu ne
doit pas être compté comme une erreur technique, et un `404` dans le scénario
A doit faire échouer la campagne.

Après la charge et la fin des requêtes en cours, vérifier les écarts entre
l'état initial et final : décrément total des lits = réservations créées =
événements `BedReserved` créés = réponses `201`. Les refus ne doivent créer
ni réservation ni événement. Rapprocher les réservations par identifiant,
pas uniquement par nombre.

## Observabilité et récupération

Collecter les mesures pendant les mêmes fenêtres temporelles :

- API/JVM : CPU, mémoire, pauses GC, threads et pool de connexions PostgreSQL.
- PostgreSQL : requêtes lentes, attentes de verrous, connexions, CPU et disque.
- Outbox : nombre d'événements non publiés et âge du plus ancien.
- Redis : latence d'écriture, mémoire et erreurs de publication.
- Générateur : CPU, mémoire, débit effectif et itérations abandonnées.

Avec 100 événements par passage et une seconde entre passages, le réglage
de référence du publieur ne peut pas suivre durablement 800 réservations/s sur une
seule instance. Il peut aussi être trop juste à 80/s selon le coût des envois.
Les campagnes de référence ont comparé des délais de 1 000 ms puis 100 ms.
Le dispatcher est désormais réveillé par `LISTEN / NOTIFY` et enchaîne les lots
de 100 événements sans délai tant qu'il reste du travail. Il attend une
notification lorsque l'outbox est vide, avec une lecture de rattrapage au
démarrage et après reconnexion. Les erreurs et les événements encore verrouillés
déclenchent une tentative différée, pas un polling permanent de la table vide.

Un retard temporaire pendant le pic est acceptable pour cette campagne si
l'API respecte ses critères. Vérifier ensuite que le retard revient au niveau
initial et que les latences nominales respectent à nouveau le seuil pendant
les cinq minutes de récupération. S'il reste du retard, le noter comme un
échec du critère de récupération ; une observation supplémentaire peut servir
au diagnostic. Aucun objectif chiffré de délai de publication de bout en bout
n'est encore défini par la spécification.

La livraison Redis étant au moins une fois, contrôler les événements uniques
par `eventId` ; le nombre brut d'entrées Redis peut inclure des doublons.
Tenir compte de la rétention du stream et des acquittements des consommateurs
pour ne pas confondre une entrée supprimée et une publication manquante.

## Exécution et rapport

Commencer par une courte exécution à faible débit pour valider les données
et les assertions, puis lancer chaque scénario depuis un état réinitialisé.
Séparer le générateur et l'application lorsque possible ; une exécution
locale Docker sert au diagnostic et ne suffit pas à établir la capacité d'un
autre environnement.

Conserver avec chaque résultat : commit, configuration matérielle, limites
des conteneurs, versions, réglages du publieur, jeu de données, débits réels,
latences par phase et statut, erreurs, cohérence et courbes de l'outbox.
Ne pas conclure à partir d'une moyenne globale. Répéter une campagne réussie
pour vérifier sa reproductibilité avant de valider le POC.

Les scripts k6, la préparation des données dédiées et les commandes d'exécution
sont disponibles dans [le guide d'exécution](../../tests/performance/README.md).
Les campagnes lourdes seront
d'abord déclenchées explicitement sur l'environnement dédié ; un contrôle
court pourra ensuite être intégré à la CI.
