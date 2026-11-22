# language: fr
#
# Scénarios d'acceptation du système d'intervention d'urgence.
#
# Le scénario 1 reprend mot pour mot celui du document d'exigences. Seuls les
# mots-clés SUPPOSONS / QUAND / ALORS / ET sont écrits avec les mots-clés
# Gherkin Soit / Quand / Alors / Et, et la ponctuation de fin de ligne est
# retirée. Le « ET » au milieu de la ligne QUAND devient une étape « Et ».
#
# Dans le PDF, la cellule de l'hôpital Beverly Bashir affiche
# « neuropathologie, diagnostic » à cause d'un retour à la ligne : il s'agit
# de la spécialité NHS « Neuropathologie diagnostique »
# (code DIAGNOSTIC_NEUROPATHOLOGY, migration V3__seed_nhs_specialties.sql).
#
# Les spécialités sont retrouvées par leur libellé dans le référentiel. Les
# hôpitaux sont créés pour chaque scénario avec des codes dédiés (PDF-…), à
# quelques kilomètres les uns des autres, puis supprimés : les scénarios ne
# dépendent jamais du jeu de démonstration.
#
# Les demandes passent par l'API HTTP (POST /api/v1/hospitals/reservations),
# avec un jeton de rôle REGULATEUR et un en-tête Idempotency-Key. L'événement
# « publié » est la ligne BedReserved écrite dans l'outbox transactionnelle,
# dans la même transaction que la réservation : c'est elle qui garantit sa
# publication dans Redis Streams.

Fonctionnalité: Réserver un lit dans l'hôpital disponible le plus proche
  En tant que régulateur des urgences
  Je veux que la demande de soins d'un patient réserve un lit dans l'hôpital
  le plus proche qui possède la spécialité demandée et un lit disponible
  Afin que le patient soit pris en charge sans délai

  Scénario: Scénario du document d'exigences
    Soit trois hôpitaux :
      | Hôpital                | Lits disponibles | Spécialisations                           |
      | Hôpital Fred Brooks    | 2                | Cardiologie, immunologie                  |
      | Hôpital Julia Crusher  | 0                | Cardiologie                               |
      | Hôpital Beverly Bashir | 5                | Immunologie, neuropathologie diagnostique |
    Et un patient nécessitant des soins en cardiologie
    Quand un patient demande des soins en cardiologie
    Et que l'urgence est localisée près de l'hôpital Fred Brooks
    Alors l'hôpital Fred Brooks devrait être proposé
    Et un événement devrait être publié pour réserver un lit

  Scénario: Un hôpital compétent mais complet n'est pas proposé, même s'il est le plus proche
    Soit trois hôpitaux :
      | Hôpital                | Lits disponibles | Spécialisations                           |
      | Hôpital Fred Brooks    | 2                | Cardiologie, immunologie                  |
      | Hôpital Julia Crusher  | 0                | Cardiologie                               |
      | Hôpital Beverly Bashir | 5                | Immunologie, neuropathologie diagnostique |
    Et un patient nécessitant des soins en cardiologie
    Quand un patient demande des soins en cardiologie
    Et que l'urgence est localisée tout près de l'hôpital Julia Crusher
    Alors l'hôpital Fred Brooks devrait être proposé
    Et un événement devrait être publié pour réserver un lit
    Mais l'hôpital Julia Crusher, pourtant plus proche, ne devrait pas être proposé

  Scénario: Une spécialité qu'aucun de ces hôpitaux ne propose est refusée
    Soit trois hôpitaux :
      | Hôpital                | Lits disponibles | Spécialisations                           |
      | Hôpital Fred Brooks    | 2                | Cardiologie, immunologie                  |
      | Hôpital Julia Crusher  | 0                | Cardiologie                               |
      | Hôpital Beverly Bashir | 5                | Immunologie, neuropathologie diagnostique |
    Et un patient nécessitant des soins en neurologie
    Quand un patient demande des soins en neurologie
    Et que l'urgence est localisée près de l'hôpital Fred Brooks
    Alors aucun hôpital ne devrait être proposé
    Et aucun lit ne devrait être réservé
    Et aucun événement ne devrait être publié
