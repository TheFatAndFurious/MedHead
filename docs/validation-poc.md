# Checklist de validation du POC MedHead

État au **4 novembre 2026**, après examen du dépôt local et des résultats disponibles.

Le back-end Java peut être validé dans l’optique d’une architecture microservice.
Les autres critères restent partiels ou sans preuve suffisante.

## Règles de validation

- Une case cochée dispose d’une preuve précisée dans ce document.
- Une case ouverte peut désigner un travail absent, partiel ou non vérifié.
- Un critère principal reste ouvert si une de ses sous-tâches reste ouverte.
- Les preuves historiques sont signalées. Elles ne remplacent pas un nouvel essai sur le code actuel.

L’examen porte sur ce dépôt, y compris ses modifications locales non commitées.
Aucun front-end ni cahier des charges complet n’a été trouvé dans ce périmètre.
La conformité à toutes les exigences reste donc à établir. Les seuils de charge
ci-dessous sont ceux définis dans le dépôt ; leur conformité au cahier des charges
reste à confirmer.

## 1. Fonctionnement du front-end et du back-end

- [ ] **Le front-end et le back-end répondent aux exigences du POC.**
  - [ ] Relier chaque exigence à une fonctionnalité réalisée. Le cahier des charges complet et la matrice de correspondance manquent.
  - [ ] Vérifier le parcours complet d’une intervention d’urgence.
    - [x] Identifier le parcours back-end : sélectionner un hôpital par proximité, spécialité et disponibilité, réserver un lit et enregistrer un événement.
    - [ ] Démontrer le parcours depuis le front-end jusqu’au résultat affiché.
  - [ ] Vérifier les principaux cas d’erreur.
    - [x] Vérifier les réponses API pour une demande invalide, une spécialité inconnue et l’absence de lit.
    - [ ] Vérifier leur affichage et leur traitement dans le front-end.
  - [x] Documenter les étapes pour démarrer et démontrer le POC existant. Le guide couvre l’API et ses dépendances ; il faudra le compléter avec le front-end.

**Preuves :** [guide de démarrage et exemple de réservation](../README.md),
[service de réservation](../src/main/java/com/example/medhead/hospital/application/ReserveNearestAvailableBedService.java),
[sélection et décrément conditionnel](../src/main/java/com/example/medhead/hospital/persistence/HospitalRepository.java),
[tests du contrôleur](../src/test/java/com/example/medhead/hospital/api/HospitalReservationControllerTest.java).
Les quatre tests du contrôleur passent lors de cet examen. Ils utilisent un service
simulé : ils ne prouvent pas à eux seuls le fonctionnement avec PostgreSQL.

## 2. Sécurité des échanges front-end / back-end

- [ ] **Les échanges entre le front-end et le back-end sont sécurisés.**
  - [ ] Vérifier l’authentification et les droits d’accès aux fonctions protégées. Aucun mécanisme utilisateur n’est présent dans le dépôt.
  - [x] Vérifier que le back-end contrôle les données reçues : champs requis, bornes des coordonnées, format et longueur du code de spécialité. Les requêtes SQL utilisent des paramètres.
  - [ ] Vérifier la configuration des origines autorisées à appeler le back-end. Aucune configuration CORS explicite ni intégration avec un front-end n’a été trouvée. Le besoin dépendra de l’hébergement retenu.
  - [ ] Vérifier le chiffrement des échanges dans l’environnement de démonstration. L’API documentée utilise HTTP ; aucun point d’entrée HTTPS n’est configuré dans ce dépôt.
  - [ ] Vérifier que le code du front-end, les réponses et les journaux ne révèlent aucun secret. Le front-end manque et aucune vérification complète des journaux n’a été réalisée.

**Preuves :** [validation de la demande](../src/main/java/com/example/medhead/hospital/api/HospitalReservationRequest.java),
[contrôleur avec validation](../src/main/java/com/example/medhead/hospital/api/HospitalReservationController.java),
[requêtes paramétrées](../src/main/java/com/example/medhead/hospital/persistence/HospitalRepository.java),
[dépendances du back-end](../pom.xml),
[configuration de l’application](../src/main/resources/application.properties),
[exposition HTTP locale](../docker-compose.yaml).

La connexion de l’API à Redis utilise TLS avec vérification du certificat et
authentification. Cette protection concerne un autre échange : elle ne valide
pas la sécurité entre le front-end et le back-end. Des mots de passe de démonstration
par défaut existent dans la configuration ; leur présence ne prouve pas une fuite
dans les réponses, mais ne permet pas non plus de cocher le contrôle des secrets.

## 3. Technologie du front-end

- [ ] **Le front-end utilise une technologie autorisée.**
  - [ ] Identifier le framework JavaScript ou TypeScript utilisé.
  - [ ] Vérifier sa conformité avec les exigences du projet.
  - [ ] Vérifier que le front-end peut être compilé et démarré selon la documentation.

**Constat :** aucun projet de front-end ni fichier `package.json` n’a été trouvé.
Le script JavaScript k6 sert aux tests de charge. Les pages HTML dans `.archify/`
sont des schémas et des explications ; elles ne constituent pas le front-end du POC.
Si le front-end existe dans un autre dépôt, son examen reste à faire.

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
  - [x] Vérifier la cohérence du formatage et du nommage dans le code examiné. Les responsabilités sont séparées entre API, application, domaine, persistance et outbox.
  - [x] Vérifier l’absence de duplication évitable dans le parcours de réservation examiné. La réservation est centralisée dans un service ; les réponses d’erreur utilisent un gestionnaire commun.
  - [x] Vérifier que les commentaires expliquent les choix utiles à comprendre : verrouillage, concurrence, commit et abonnement aux notifications PostgreSQL.
  - [x] Vérifier la gestion des erreurs et la clarté des messages : erreurs métier structurées et reprise de publication après échec.
  - [ ] Vérifier que la documentation permet de reprendre le projet.
    - [x] Fournir les procédures de démarrage, de test et de charge dans les README.
    - [ ] Actualiser les schémas d’architecture et de séquence conservés dans `.archify/`. Ils montrent encore une recherche séparée et/ou un publieur périodique, alors que le code utilise désormais la réservation directe et `LISTEN / NOTIFY`.

**Preuves :** [service métier](../src/main/java/com/example/medhead/hospital/application/ReserveNearestAvailableBedService.java),
[gestion des erreurs API](../src/main/java/com/example/medhead/hospital/api/ApiExceptionHandler.java),
[reprise après erreur de publication](../src/main/java/com/example/medhead/outbox/OutboxPublisher.java),
[écoute des notifications](../src/main/java/com/example/medhead/outbox/OutboxNotificationListener.java),
[ancien schéma d’architecture](../.archify/architecture-medhead-20261019-0900/candidate.json),
[ancien schéma de séquence](../.archify/sequence-outbox-redis-20261028-150915/candidate.json).
Cette revue est qualitative et limitée au code existant. Aucun contrôle automatisé
de style ou de duplication n’a été exécuté.

## 6. Tests du système d’intervention d’urgence

- [ ] **Les tests vérifient le système d’intervention d’urgence.**
  - [ ] Définir les scénarios de test à partir des exigences. Les scénarios du parcours de réservation existent, mais leur correspondance avec toutes les exigences manque.
  - [x] Tester les règles métier du système sur le périmètre de réservation existant : capacité, coordonnées, spécialité, proximité, concurrence et annulation de transaction. Les essais avec base disposent de preuves historiques.
  - [ ] Tester les échanges entre les composants.
    - [x] Disposer de tests API / PostgreSQL et PostgreSQL / Redis TLS, avec des rapports historiques réussis.
    - [ ] Confirmer leur réussite sur le code actuel avec les deux suites d’intégration activées.
    - [ ] Tester les échanges front-end / back-end.
  - [ ] Tester un parcours complet depuis le front-end.
  - [ ] Tester les cas d’échec et les accès non autorisés.
    - [x] Tester les erreurs API, les écritures annulées et les échecs de publication. Les essais Redis disposent de preuves historiques.
    - [ ] Tester les accès utilisateur non autorisés après mise en place des contrôles d’accès.
  - [x] Conserver les résultats et identifier les limites de couverture. Voir le bilan ci-dessous.

**Preuves :** [tests du domaine](../src/test/java/com/example/medhead/hospital/domain/HospitalTest.java),
[tests du contrôleur](../src/test/java/com/example/medhead/hospital/api/HospitalReservationControllerTest.java),
[tests de réservation et de concurrence](../src/test/java/com/example/medhead/hospital/application/BedReservationIntegrationTest.java),
[tests de publication et de reprise](../src/test/java/com/example/medhead/outbox/OutboxPublicationIntegrationTest.java),
[tests des métriques du publieur](../src/test/java/com/example/medhead/outbox/OutboxPublisherMetricsTest.java).

### Résultats disponibles

| Vérification | Résultat | Portée |
| --- | --- | --- |
| Tests Java relancés le 4 novembre, avec Java 21 | 12 réussis, 22 ignorés, aucun échec ni erreur | 4 tests du contrôleur, 5 du domaine, 3 des métriques ; suites d’intégration désactivées par défaut |
| Tests Python relancés le 4 novembre | 4 réussis | Collecte et calcul des métriques de charge ; ne valide pas la capacité sous charge |
| Rapports Java présents avant cet examen, datés du 28 octobre | 34 réussis, aucun ignoré | Dont 7 tests PostgreSQL et 15 tests PostgreSQL / Redis TLS ; preuve historique, sans garantie d’identité avec tout l’état local actuel |

Les rapports historiques ont été copiés avant la relance dans
`.local/validation-poc/2026-11-04/previous-surefire-reports/`.
Les nouveaux rapports Maven sont dans `target/surefire-reports/`.
Ces deux répertoires sont locaux et exclus du versionnement.

Commandes exécutées lors de cet examen :

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21.jdk/Contents/Home \
  ./mvnw --offline --batch-mode --no-transfer-progress test
python3 -m unittest discover -s tests/performance -p 'test_*.py' -v
```

Docker n’était pas démarré. Les tests d’intégration et les campagnes de charge
n’ont donc pas été relancés. Le workflow de CI est présent dans le dépôt, mais
aucune exécution distante n’a été vérifiée pendant cet examen.

## 7. Montée en charge du back-end

- [ ] **Les tests vérifient la capacité du back-end à assurer une montée en charge.**
  - [x] Définir la charge cible et les seuils acceptables : 80 requêtes/s, pic à 800 requêtes/s, p95 inférieur à 200 ms, aucun échec technique ni itération abandonnée. Le p95 est la durée sous laquelle se terminent 95 % des requêtes.
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

**Constat :** des supports techniques existent, dont les tests, la stratégie de
charge et le [workflow de CI](../.github/workflows/ci.yml). Aucun support de
présentation du pilotage d’équipe n’a été trouvé. Le code seul ne prouve pas la
capacité à expliquer cette approche ; ces cases restent ouvertes jusqu’à une
préparation ou une démonstration explicite.
