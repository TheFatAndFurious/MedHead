# Checklist de validation du POC MedHead

État au **4 novembre 2026, deuxième revue**, après lecture du document des exigences,
examen du dépôt local, exécution des tests et vérification du front-end dans le navigateur.

Le choix du framework du front-end et le back-end Java orienté microservice sont
validés. Le parcours de démonstration fonctionne. Les autres critères restent
partiels, notamment la protection des données, les tests E2E automatisés, la
charge et les livrables documentaires.

## Règles de validation

- Une case cochée dispose d’une preuve précisée dans ce document.
- Une case ouverte peut désigner un travail absent, partiel ou non vérifié.
- Un critère principal reste ouvert si une de ses sous-tâches reste ouverte.
- Les preuves historiques sont signalées. Elles ne remplacent pas un nouvel essai sur le code actuel.

L’examen porte sur ce dépôt, y compris ses modifications locales non commitées.
Le front-end est désormais présent dans `frontend/`. Les preuves locales de cette
revue sont conservées dans `.local/validation-poc/2026-11-04-revue-2/`, exclu du
versionnement. Une compilation ou un essai manuel ne constitue pas un test E2E
automatisé.

## Référence et correspondance avec les exigences

Source : document fourni « Exigences pour le développement d’une preuve de concept
pour le système d’intervention d’urgence en temps réel », trois pages. Empreinte
SHA-256 : `ffe9646750b55170c2d113d7079f47c9e6c67e5c82c994c37d6c0ee97e96aafa`.
Les exigences servent de critères d’évaluation ; elles ne sont pas des instructions
pour modifier l’application pendant cette revue.

**Dérogation confirmée par l’utilisateur pendant cette revue :** SvelteKit est
autorisé à la place d’Angular, React ou VueJS. Cette dérogation est aussi mentionnée
dans le [README du front-end](../frontend/README.md).

| Exigence du PDF | Page | Preuve ou écart actuel | Checklist |
| --- | --- | --- | --- |
| API REST Java, compatible à terme avec une architecture microservice | 2 | API Spring Boot, contrat JSON et événements ; validé sur ce périmètre | 1, 4 |
| Hôpital le plus proche, lit disponible et spécialité adaptée | 1–2 | Service de réservation et tests de sélection, de refus et de concurrence réussis | 1, 6 |
| Événement publié pour réserver un lit | 2 | Le code réserve d’abord le lit et publie ensuite `BedReserved` ; expliquer cette interprétation du scénario | 1, 4 |
| Informations récentes sur les hôpitaux et référentiel de spécialités | 1–2 | Décrément des lits transactionnel et 80 spécialités chargées ; actualisation externe et correspondance exacte au référentiel à confirmer | 1 |
| Page de sélection de spécialité et de saisie de localisation | 2 | Page fonctionnelle ; localisation limitée à huit secteurs prédéfinis | 1, 3 |
| Angular, React ou VueJS | 2 | SvelteKit / TypeScript autorisé par dérogation confirmée | 3 |
| Protection de toutes les données du patient | 2 | Données fictives, validation serveur et Redis TLS ; protection complète non démontrée | 2 |
| Pyramide de tests : unitaires, intégration et E2E | 2 | 38 tests Java réussis et deux parcours manuels ; aucun test E2E automatisé trouvé | 6 |
| Tests de stress et continuité pendant un pic | 2 | Campagnes k6 disponibles ; campagnes complètes historiques en échec | 7 |
| Moins de 200 ms jusqu’à 800 requêtes/s par instance | 1 | Pic à 800 prévu ; le p95 choisi dans k6 ne précise pas à lui seul le sens du seuil du PDF | 7 |
| Code partageable, CI/CD et réutilisation par les équipes | 3 | Docker, migrations, README et CI back-end présents ; livraison continue à formaliser | 4, 5 |
| Versionnement avec un workflow Git adapté | 3 | Dépôt et commits présents ; nouveaux fichiers non suivis et workflow à documenter | 5 |
| README Markdown : tests, pipeline et workflow Git détaillé | 3 | Tests et CI décrits ; workflow Git réutilisable manquant | 5 |
| Rapport : technologies, normes, principes, résultats et enseignements | 3 | Notes techniques et résultats partiels ; rapport complet non trouvé | 5, 8 |
| Plus de 90 % d’orientations vers le bon hôpital et temps moyen réduit de 18,25 à 12 minutes | 1 | Objectifs métier identifiés ; aucune mesure de terrain trouvée | 8 |
| Respect des normes et du délai imparti | 1 | Justifications de conformité et bilan de planning à produire | 2, 5, 8 |

## 1. Fonctionnement du front-end et du back-end

- [ ] **Le front-end et le back-end répondent aux exigences du POC.**
  - [x] Relier les exigences du PDF aux fonctions, preuves et écarts. Voir la matrice ci-dessus ; une correspondance documentée ne signifie pas que toute l’exigence est satisfaite.
  - [x] Vérifier le parcours de démonstration d’une intervention d’urgence.
    - [x] Vérifier le parcours back-end : sélectionner un hôpital par proximité, spécialité et disponibilité, réserver un lit et enregistrer un événement.
    - [x] Démontrer le parcours depuis le front-end jusqu’au résultat affiché : Westminster / Cardiologie renvoie MedHead Westminster et une référence de réservation.
  - [ ] Vérifier les principaux cas d’erreur.
    - [x] Vérifier les réponses API pour une demande invalide, une spécialité inconnue et l’absence de lit.
    - [x] Vérifier dans le navigateur le message d’absence de lit avec Westminster / Allergie.
    - [ ] Vérifier dans le navigateur l’indisponibilité du catalogue, le refus d’une demande invalide et une confirmation perdue. Ces traitements sont codés, mais n’ont pas été exercés pendant cette revue.
  - [x] Documenter les étapes pour démarrer et démontrer le POC, front-end compris.
  - [ ] Permettre la saisie de localisation attendue par le PDF, ou documenter une acceptation du choix limité à huit secteurs de démonstration. La dérogation confirmée porte uniquement sur le framework.
  - [ ] Confirmer que les 80 spécialités chargées correspondent exactement au référentiel cité dans le PDF.
  - [ ] Décrire comment les données des hôpitaux restent récentes. Le code actualise les lits lors des réservations, mais aucune alimentation externe n’a été trouvée.
  - [ ] Justifier le choix « réserver puis publier `BedReserved` » au regard du scénario « publier un événement pour réserver un lit ».

**Preuves :** [guide de démarrage et exemple de réservation](../README.md),
[service de réservation](../src/main/java/com/example/medhead/hospital/application/ReserveNearestAvailableBedService.java),
[sélection et décrément conditionnel](../src/main/java/com/example/medhead/hospital/persistence/HospitalRepository.java),
[tests du contrôleur](../src/test/java/com/example/medhead/hospital/api/HospitalReservationControllerTest.java),
[page de réservation](../frontend/src/routes/+page.svelte),
[appels serveur vers l’API](../frontend/src/routes/+page.server.ts),
[guide du front-end](../frontend/README.md).
Les tests avec PostgreSQL et Redis passent lors de cette deuxième revue.
L’essai navigateur a créé la réservation fictive
`70c9f856-2d74-4b38-b83b-1014b7ccc0de` et affiché 15 lits restants, une distance
de 0,72 km et les coordonnées de l’hôpital. Il a consommé un lit de démonstration.
Le refus faute de lit a ensuite été observé dans le même navigateur.

## 2. Sécurité des échanges front-end / back-end

- [ ] **Les échanges entre le front-end et le back-end sont sécurisés.**
  - [ ] Définir et vérifier les accès autorisés aux données et aux réservations. Keycloak, les sessions serveur SvelteKit / Redis et les rôles `LECTEUR` / `REGULATEUR` sont maintenant implémentés. Les tests de sécurité Spring et les tests de session passent ; le parcours complet avec les conteneurs reste à valider. Voir [le guide d’authentification](authentification.md). Le PDF impose une protection des données sans prescrire un mécanisme précis.
  - [x] Vérifier que le back-end contrôle les données reçues : champs requis, bornes des coordonnées, format et longueur du code de spécialité. Les requêtes SQL utilisent des paramètres.
  - [x] Vérifier le chemin des appels : le navigateur communique avec SvelteKit ; le serveur SvelteKit appelle Spring. Le parcours actuel ne nécessite pas un appel du navigateur vers une autre origine API ni une ouverture CORS pour cet échange.
  - [ ] Vérifier le chiffrement des échanges dans l’environnement de démonstration. L’API documentée utilise HTTP ; aucun point d’entrée HTTPS n’est configuré dans ce dépôt.
  - [ ] Vérifier que le code du front-end, les réponses et les journaux ne révèlent aucun secret. Les appels API et leur URL sont placés dans le module serveur ; la vérification complète des ressources servies et des journaux reste à faire.
  - [ ] Documenter la protection des données du patient : localisation et spécialité transmises, données conservées, accès, durée de conservation et règles applicables. L’absence de nom de patient ne suffit pas à valider ce point.

**Preuves :** [validation de la demande](../src/main/java/com/example/medhead/hospital/api/HospitalReservationRequest.java),
[contrôleur avec validation](../src/main/java/com/example/medhead/hospital/api/HospitalReservationController.java),
[requêtes paramétrées](../src/main/java/com/example/medhead/hospital/persistence/HospitalRepository.java),
[dépendances du back-end](../pom.xml),
[configuration de l’application](../src/main/resources/application.properties),
[exposition HTTP locale](../docker-compose.yaml),
[actions SvelteKit côté serveur](../frontend/src/routes/+page.server.ts).

La connexion de l’API à Redis utilise TLS avec vérification du certificat et
authentification. Cette protection concerne un autre échange : elle ne valide
pas la sécurité entre le front-end et le back-end. Des mots de passe de démonstration
par défaut existent dans la configuration ; leur présence ne prouve pas une fuite
dans les réponses, mais ne permet pas non plus de cocher le contrôle des secrets.

## 3. Technologie du front-end

- [x] **Le front-end utilise une technologie autorisée.**
  - [x] Identifier le framework JavaScript ou TypeScript utilisé : SvelteKit, Svelte et TypeScript.
  - [x] Vérifier sa conformité avec les exigences du projet : dérogation SvelteKit confirmée par l’utilisateur pendant cette revue.
  - [x] Vérifier que le front-end peut être compilé et démarré selon la documentation : service Docker accessible, `npm run check` et `npm run build` réussis.

**Preuves :** [dépendances et commandes](../frontend/package.json),
[configuration SvelteKit](../frontend/vite.config.ts),
[image du front-end](../frontend/Dockerfile),
[dérogation et démarrage documentés](../frontend/README.md).
La vérification Svelte indique zéro erreur et zéro avertissement. La compilation
avec l’adaptateur Node réussit. Le service Compose exécute l’application
compilée avec un utilisateur non privilégié ; le serveur de développement est
réservé au fichier `docker-compose.dev.yaml`. Le déploiement de production
(HTTPS, en-têtes de sécurité, secrets) reste un sujet distinct.

## 4. Back-end Java et architecture microservice

- [x] **Le back-end est développé en Java et prépare une architecture microservice.**
  - [x] Vérifier que les fonctions du back-end sont développées en Java. Le projet cible Java 21 et utilise Spring Boot.
  - [x] Expliquer les responsabilités et les limites des services prévus. Le service actuel porte la réservation ; les futurs services consommateurs reçoivent les événements par groupes Redis distincts.
  - [x] Documenter les interfaces utilisées pour communiquer avec le back-end. Le README décrit l’entrée JSON, la réponse, les erreurs et les champs des événements.
  - [x] Expliquer les choix du POC et les évolutions nécessaires vers l’architecture cible. Le README décrit la transaction, l’outbox, la livraison au moins une fois et l’obligation de rendre les futurs consommateurs idempotents, pour éviter de traiter deux fois le même événement.

**Preuves :** [configuration Java](../pom.xml),
[application Spring Boot](../src/main/java/com/example/medhead/MedHeadApplication.java),
[API de réservation](../src/main/java/com/example/medhead/hospital/api/HospitalReservationController.java),
[publication des événements](../src/main/java/com/example/medhead/outbox/OutboxPublicationService.java),
[documentation du contrat et des futurs consommateurs](../README.md).

La validation porte sur l’orientation demandée : un service métier Java avec une
API et des événements pour ses futurs consommateurs. Le dépôt ne contient pas
encore un système composé de plusieurs microservices métier déployés.

## 5. Bonnes pratiques

- [ ] **Le code applique les bonnes pratiques.**
  - [x] Vérifier la cohérence du formatage et du nommage dans le code examiné. Les responsabilités sont séparées entre API, application, domaine, persistance et outbox. Le sens des dépendances (`api` → `application` → `persistence` → `domain`) est vérifié automatiquement par une règle ArchUnit ([test d’architecture](../src/test/java/com/example/medhead/ArchitectureTest.java)) : la couche application ne connaît pas les DTO HTTP.
  - [x] Vérifier l’absence de duplication évitable dans le parcours de réservation examiné. La réservation est centralisée dans un service ; les réponses d’erreur utilisent un gestionnaire commun.
  - [x] Vérifier que les commentaires expliquent les choix utiles à comprendre : verrouillage, concurrence, commit et abonnement aux notifications PostgreSQL.
  - [x] Vérifier la gestion des erreurs et la clarté des messages : erreurs métier structurées et reprise de publication après échec.
  - [ ] Vérifier que la documentation permet de reprendre le projet.
    - [x] Fournir les procédures de démarrage, de test et de charge dans les README.
    - [ ] Actualiser les schémas d’architecture et de séquence conservés dans `.archify/`. Ils montrent encore une recherche séparée et/ou un publieur périodique, alors que le code utilise désormais la réservation directe et `LISTEN / NOTIFY`.
  - [ ] Couvrir les exigences de CI/CD et de partage du PDF, page 3.
    - [x] Fournir un workflow de CI pour tester le back-end, produire le JAR et construire son image Docker.
    - [x] Décrire dans le README l’exécution des tests et le fonctionnement du pipeline.
    - [ ] Vérifier une exécution distante réussie de ce workflow. Seule sa définition locale a été examinée.
    - [ ] Ajouter la vérification et la compilation du front-end au pipeline. Le workflow actuel couvre seulement le back-end.
    - [ ] Formaliser la livraison continue : version du livrable, publication et réutilisation par les équipes. Le JAR est prévu comme artefact CI ; aucun pipeline de livraison distinct n’a été trouvé.
  - [ ] Compléter le versionnement et le workflow Git.
    - [x] Disposer d’un dépôt Git avec un historique de commits, dont l’ajout du front-end.
    - [ ] Versionner les nouveaux fichiers utiles actuellement non suivis, notamment les actions du front-end, le catalogue API, ses tests et le workflow de CI.
    - [ ] Décrire dans le README un workflow Git réutilisable : branches, revue, contrôles avant fusion et livraison. La description de la CI ne remplace pas ce workflow.
  - [ ] Fournir le rapport demandé par le PDF, page 3.
    - [ ] Rassembler les justifications des technologies utilisées, dont la dérogation SvelteKit.
    - [ ] Justifier les normes et principes retenus, dont la protection des données et l’orientation microservice.
    - [ ] Présenter les résultats, les limites et les enseignements du POC dans un rapport identifié. La checklist et les notes de charge apportent des éléments, mais ne constituent pas encore ce rapport complet.

**Preuves :** [service métier](../src/main/java/com/example/medhead/hospital/application/ReserveNearestAvailableBedService.java),
[gestion des erreurs API](../src/main/java/com/example/medhead/hospital/api/ApiExceptionHandler.java),
[reprise après erreur de publication](../src/main/java/com/example/medhead/outbox/OutboxPublisher.java),
[écoute des notifications](../src/main/java/com/example/medhead/outbox/OutboxNotificationListener.java),
[ancien schéma d’architecture](../.archify/architecture-medhead-20261019-0900/candidate.json),
[ancien schéma de séquence](../.archify/sequence-outbox-redis-20261028-150915/candidate.json).
Cette revue est qualitative et limitée au code existant. Aucun contrôle automatisé
de style ou de duplication n’a été exécuté. Les contrôles Svelte / TypeScript et
la compilation ont été exécutés ; ils ne remplacent pas une analyse de duplication.
Le front-end évite les relances automatiques. En cas de confirmation incertaine,
il propose de renvoyer la même demande avec la même clé `Idempotency-Key` :
l’API renvoie alors la réservation déjà créée sans consommer de second lit.
Ce comportement est couvert par les tests de l’action SvelteKit
(`frontend/tests/page-server.test.ts`) et par les tests d’intégration de la
réservation (rejeu, clé réutilisée, envois simultanés).

## 6. Tests du système d’intervention d’urgence

- [ ] **Les tests vérifient le système d’intervention d’urgence.**
  - [x] Relier les familles de tests aux exigences : règles de réservation, erreurs, concurrence, événement et catalogue. La matrice distingue aussi la couverture E2E et de stress encore insuffisante.
  - [x] Tester les règles métier du système sur le périmètre de réservation existant : capacité, coordonnées, spécialité, proximité, concurrence et annulation de transaction. Les tests avec base ont été relancés avec succès.
  - [x] Tester les échanges entre les composants.
    - [x] Exécuter les tests API / PostgreSQL et PostgreSQL / Redis TLS.
    - [x] Confirmer leur réussite sur le code actuel avec les deux options d’intégration activées.
    - [x] Vérifier manuellement les échanges front-end / back-end pour une réservation réussie et un refus faute de lit.
  - [ ] Automatiser les tests de bout en bout depuis le navigateur pour compléter la pyramide de tests exigée. Aucun outil ni scénario E2E automatisé n’a été trouvé.
  - [ ] Tester les cas d’échec et les accès non autorisés.
    - [x] Tester les erreurs API, les écritures annulées et les échecs de publication, avec PostgreSQL et Redis TLS.
    - [ ] Tester les accès non autorisés selon la politique de protection retenue.
  - [ ] Couvrir automatiquement les actions serveur du front-end : formulaire invalide, catalogue indisponible, erreur API et confirmation incertaine. `npm run check` contrôle les types et les composants ; ce n’est pas un test fonctionnel.
  - [x] Conserver les résultats et identifier les limites de couverture. Voir le bilan ci-dessous.

**Preuves :** [tests du domaine](../src/test/java/com/example/medhead/hospital/domain/HospitalTest.java),
[tests du contrôleur](../src/test/java/com/example/medhead/hospital/api/HospitalReservationControllerTest.java),
[tests de réservation et de concurrence](../src/test/java/com/example/medhead/hospital/application/BedReservationIntegrationTest.java),
[tests de publication et de reprise](../src/test/java/com/example/medhead/outbox/OutboxPublicationIntegrationTest.java),
[tests des métriques du publieur](../src/test/java/com/example/medhead/outbox/OutboxPublisherMetricsTest.java),
[nouveaux tests du catalogue](../src/test/java/com/example/medhead/hospital/api/HospitalCatalogIntegrationTest.java).

### Résultats disponibles

| Vérification | Résultat | Portée |
| --- | --- | --- |
| Tests Java relancés pendant cette deuxième revue, avec Java 21 | 38 réussis, aucun ignoré, aucun échec ni erreur | 4 tests du catalogue, 4 du contrôleur, 7 de réservation avec PostgreSQL, 5 du domaine, 15 de publication PostgreSQL / Redis TLS et 3 des métriques |
| Tests Python relancés pendant cette deuxième revue | 4 réussis | Collecte et calcul des métriques de charge ; ne valide pas la capacité sous charge |
| Vérification du front-end | Zéro erreur, zéro avertissement | Composants Svelte et types TypeScript |
| Compilation du front-end | Réussie | Application construite avec l’adaptateur Node |
| Essais navigateur | Réservation confirmée et refus faute de lit observés | Vérifications manuelles, sans suite E2E automatisée |
| Rapports historiques du 28 octobre conservés lors de la première revue | 34 réussis, aucun ignoré | Dont 7 tests PostgreSQL et 15 tests PostgreSQL / Redis TLS ; preuve historique, sans garantie d’identité avec tout l’état local actuel |

Les rapports historiques du 28 octobre restent conservés dans
`.local/validation-poc/2026-11-04/previous-surefire-reports/`.
Ceux présents avant la deuxième revue ont été copiés dans
`.local/validation-poc/2026-11-04-revue-2/previous-surefire-reports/`.
Les nouveaux rapports Maven sont dans `target/surefire-reports/`, avec une copie
dans `.local/validation-poc/2026-11-04-revue-2/surefire-reports/`.
Le journal Maven, le résumé JSON et les captures navigateur se trouvent dans ce
même dossier de revue. Tous ces répertoires sont locaux et exclus du versionnement.

Commandes principales exécutées pendant cette deuxième revue :

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21.jdk/Contents/Home \
  ./mvnw --offline --batch-mode --no-transfer-progress \
  -Dmedhead.integration-tests=true \
  -Dmedhead.redis-integration-tests=true \
  -Dmedhead.test.jdbc-url=jdbc:postgresql://localhost:55442/hospital_test \
  -Dmedhead.test.redis-port=56389 \
  -Dmedhead.test.redis-ca=file:./.local/validation-poc/2026-11-04-revue-2/ca.crt test
python3 -m unittest discover -s tests/performance -p 'test_*.py' -v
docker compose exec -T frontend npm run check
docker compose exec -T frontend npm run build
```

Les tests Java ont utilisé un projet Compose séparé, `medhead-validation-poc`,
avec les ports `55442` et `56389`. Le certificat public Redis a été copié depuis
ce projet avant l’exécution. Les conteneurs et volumes créés pour ce test ont
ensuite été supprimés. Ces commandes supposent donc de préparer à nouveau cet
environnement avant une relance. Les services de démonstration ont été conservés.
Aucune nouvelle campagne de charge ni exécution distante de la CI n’a été
vérifiée pendant cette revue.

## 7. Montée en charge du back-end

- [ ] **Les tests vérifient la capacité du back-end à assurer une montée en charge.**
  - [ ] Aligner précisément les critères de mesure sur le PDF : moins de 200 ms avec une charge allant jusqu’à 800 requêtes/s, par instance de service.
    - [x] Identifier le profil déjà implémenté : nominal à 80 requêtes/s, pic d’une minute à 800 requêtes/s, p95 inférieur à 200 ms, aucun échec technique ni itération abandonnée.
    - [ ] Préciser le sens du seuil de 200 ms et la durée à garantir à 800 requêtes/s. Le PDF ne mentionne ni p95 ni durée de pic. Le p95, durée sous laquelle se terminent 95 % des requêtes, est un choix du dépôt et ne suffit pas à démontrer toute interprétation du seuil.
    - [ ] Rapporter les résultats et les ressources allouées pour une instance de service identifiée.
  - [x] Préparer un scénario de charge représentatif du périmètre actuel : réservations réussies et flux mixte avec refus faute de lit, données synthétiques et environnement isolé.
  - [ ] Exécuter les tests avec une charge progressive sur la version actuelle.
    - [x] Conserver des campagnes complètes historiques pour le scénario de réservations réussies.
    - [ ] Relancer ce scénario après le passage du publieur à `LISTEN / NOTIFY`.
    - [ ] Exécuter une campagne complète du scénario mixte. Seul un essai court a été trouvé pour ce mode.
  - [ ] Mesurer les performances et l’utilisation des ressources.
    - [x] Collecter les latences HTTP, les réponses, les itérations abandonnées, le retard de publication et les durées du publieur.
    - [ ] Conserver des mesures des ressources pendant la campagne : API/JVM, base, Redis et générateur. Le guide précise que cette collecte reste à compléter.
  - [x] Comparer les résultats aux seuils définis. Les rapports historiques permettent de constater les échecs ; comparer ne signifie pas que les seuils sont atteints.
  - [x] Documenter les limites observées et les améliorations possibles : retard de l’outbox, essais locaux avec ressources partagées et nouvelles campagnes nécessaires après changement du publieur.

**Preuves :** [stratégie de charge](performance/strategie-tests-charge.md),
[guide d’exécution](../tests/performance/README.md),
[scénarios k6 et seuils](../tests/performance/reservations.js),
[préparation et contrôles de campagne](../tests/performance/run.py),
[contrôle de cohérence SQL](../tests/performance/verify.sql).

### Résultats de charge conservés

Les fichiers bruts se trouvent dans `.local/performance/<identifiant>/`.
Ils sont locaux et exclus du versionnement. Les campagnes ont enregistré le
commit `9c81e1765911edaa04ee24575f5035bb57eb0a85` avec des modifications locales :
ce numéro seul ne permet pas d’identifier exactement le code testé.

| Identifiant du 28 octobre | Type | Résultat et limite |
| --- | --- | --- |
| `20261028T101707685277Z-mixed` | Court, mixte | k6 réussit ; 50 réservations, aucun événement en attente à la fin. Ne teste pas le pic à 800 requêtes/s. |
| `20261028T102347866777Z-success` | Complet | Échec : 10 itérations abandonnées et 28 821 événements encore en attente à la fin. |
| `20261028T143854430450Z-success` | Complet | 100 801 réservations, aucun échec HTTP ni itération abandonnée ; p95 nominal 5,568 ms, pic 5,718 ms, récupération 5,074 ms. Échec global : 44 293 événements encore en attente au relevé de fin. |
| `20261028T153943655704Z-success` | Complet | 100 801 réservations sur 100 802 requêtes ; un échec HTTP et 1 911 événements encore en attente au relevé de fin. Échec global. |
| `20261028T214120910357Z-success` | Dernier essai court trouvé | 100 réservations, k6 réussit, p95 31,902 ms et aucun événement en attente à la fin. Ne valide pas une campagne complète. |

Les contrôles SQL de ces campagnes complètes indiquent une cohérence entre lits
consommés, réservations et événements enregistrés. Cela ne suffit pas à valider
la publication dans le délai prévu. Les nombres d’événements en attente cités
viennent de `verdict.json`, au relevé de fin ; un contrôle SQL ultérieur peut
observer un nombre plus faible, car la publication continue entre les relevés.

## 8. Pilotage d’une équipe

- [ ] **L’approche de pilotage d’une équipe peut être expliquée.**
  - [ ] Présenter l’approche de développement du POC et justifier les priorités.
  - [ ] Expliquer la répartition des rôles et des tâches.
  - [ ] Expliquer le suivi de l’avancement, des risques et des blocages.
  - [ ] Décrire le soutien apporté à l’équipe pendant le cadrage, le développement, les tests et la démonstration.
  - [ ] Expliquer comment les revues de code, les tests et les retours permettent de maintenir la qualité.
  - [ ] Préparer un bilan : résultats, limites et prochaines étapes.
    - [ ] Définir une méthode de mesure pour l’objectif de plus de 90 % d’orientations vers l’hôpital compétent le plus proche.
    - [ ] Définir une méthode de mesure pour le passage de 18,25 à 12 minutes de traitement moyen. Le temps de réponse de l’API ne mesure pas ce délai métier.
    - [ ] Comparer le planning réalisé au délai imparti et expliquer les écarts. Aucune échéance précise n’est donnée dans le PDF fourni.

**Constat :** des supports techniques existent, dont les tests, la stratégie de
charge et le [workflow de CI](../.github/workflows/ci.yml). Aucun support de
présentation du pilotage d’équipe n’a été trouvé. Le code seul ne prouve pas la
capacité à expliquer cette approche ; ces cases restent ouvertes jusqu’à une
préparation ou une démonstration explicite.
