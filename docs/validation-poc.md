# Validation de la PoC MedHead

La checklist de validation du 4 novembre 2026 est remplacée par le
**[rapport de la PoC](https://github.com/TheFatAndFurious/MedHead-architecture/blob/master/reporting/rapport-poc.md)**,
rédigé dans le dépôt d'architecture
[TheFatAndFurious/MedHead-architecture](https://github.com/TheFatAndFurious/MedHead-architecture).

Cette checklist était devenue partiellement périmée : l'authentification
Keycloak, la CI du front, les tests d'acceptation Cucumber, les tests E2E
Playwright, la publication des images et les campagnes de charge du
22 novembre 2026 ont été ajoutés depuis. Le rapport reprend chacun de ses
critères avec des preuves à jour :

| Sujet de l'ancienne checklist | Section du rapport |
| --- | --- |
| Correspondance avec les exigences du PDF | 1. Contexte, objectifs et périmètre |
| Technologie du front-end et back-end Java | 2. Justification des technologies |
| Sécurité des échanges et protection des données | 3.1 RGPD |
| Architecture microservice et événements | 3.2 et 3.3 |
| Bonnes pratiques, CI/CD et workflow Git | 3.4 Matrice des principes, 5.5 Retour sur la démarche |
| Tests du système d'intervention d'urgence | 4.1 Résultats fonctionnels |
| Montée en charge du back-end | 4.2 Performance de l'API |
| Bilan, objectifs métier et prochaines étapes | 5. Enseignements |

Le détail de la procédure et des résultats de charge reste dans ce dépôt :
[stratégie](performance/strategie-tests-charge.md) et
[résultats du 22 novembre 2026](performance/resultats-2026-11-22.md).

La version historique de la checklist reste consultable au commit
[`ce8dbf4`](https://github.com/TheFatAndFurious/MedHead/blob/ce8dbf4de37e7868b64ddca32df5210ce0705fe8/docs/validation-poc.md).
