# Authentification et autorisations

MedHead utilise Keycloak comme fournisseur OpenID Connect. Le navigateur
communique avec SvelteKit, qui conserve les jetons côté serveur et appelle
l’API Spring avec `Authorization: Bearer`. Le navigateur ne reçoit aucun
jeton d’accès ou de renouvellement, ni secret de client OAuth.

## Démarrer la démonstration

```bash
docker compose up --build
```

Utiliser <http://localhost:5173>, puis « Se connecter ». Les valeurs initiales
suivantes sont des identifiants de démonstration locale :

| Compte | Mot de passe | Rôle |
| --- | --- | --- |
| `lecteur` | `lecteur_demo` | `LECTEUR` |
| `regulateur` | `regulateur_demo` | `REGULATEUR` |

Keycloak est accessible sur <http://localhost:8180>, avec l’administrateur
initial `admin` / `medhead_admin_demo`. L’administrateur Keycloak ne reçoit
aucun droit métier MedHead automatiquement.

La base PostgreSQL de Keycloak est indépendante de la base des hôpitaux.
Le volume `keycloak-data` conserve comptes, clients, rôles et sessions SSO.
Le realm `medhead` est importé depuis `docker/keycloak/medhead-realm.json`
uniquement s’il n’existe pas encore. Modifier le JSON ou les variables des
mots de passe ne met donc pas à jour un realm déjà importé : utiliser ensuite
la console Keycloak pour modifier les comptes, le secret et les URL du client.

## Session web

- `/auth/login` démarre un flux Authorization Code avec PKCE S256, `state`
  et `nonce`. La transaction est conservée dix minutes dans Redis et liée à
  un cookie temporaire `HttpOnly`. Le callback la consomme une seule fois.
- `/auth/callback` valide le retour et l’identité avec `openid-client`, y compris
  la signature de l’ID token. Il crée un nouvel identifiant aléatoire de
  256 bits et invalide une éventuelle session précédente.
- Redis conserve les jetons et l’identité dans sa base logique 1, sous le
  préfixe `medhead:auth:`. Le transport utilise le même TLS vérifié et le même
  mot de passe que les autres clients Redis. La base 0 reste utilisée par
  l’outbox et les streams. Ces bases logiques partagent les ressources Redis ;
  elles ne constituent pas une isolation de sécurité entre services.
- La session web expire au plus tard après huit heures. Le realm configure
  aussi une inactivité SSO de trente minutes et un jeton d’accès de cinq minutes.
  Un refresh token refusé invalide la session locale. Une panne temporaire
  échoue en `503` sans autoriser la requête protégée.
- Les renouvellements sont sérialisés par un verrou Redis. La sauvegarde d’un
  renouvellement exige que la session existe encore : une déconnexion concurrente
  ne peut pas être annulée par un renouvellement déjà en cours.
- `POST /auth/logout` supprime immédiatement la session locale, tente de révoquer
  le refresh token et redirige vers la déconnexion SSO. Keycloak peut demander
  confirmation, car aucun ID token n’est envoyé dans l’URL du navigateur.
  En cas d’indisponibilité de Keycloak, la déconnexion locale reste effective.

Le cookie de session est `HttpOnly`, `SameSite=Lax`, limité au chemin `/`, sans
attribut `Domain`. En HTTPS, il porte le préfixe `__Host-` et l’attribut `Secure`.
Toutes les requêtes de modification vers SvelteKit exigent un en-tête `Origin`
égal à `MEDHEAD_PUBLIC_ORIGIN`, y compris les requêtes JSON et la déconnexion.
La protection d’origine native de SvelteKit reste également active.
Les réponses ne doivent pas être mises en cache et utilisent `no-referrer`.

## Contrôle des droits

| Route API | Accès |
| --- | --- |
| `GET /api/v1/hospitals` | `LECTEUR` ou `REGULATEUR` |
| `GET /api/v1/specialties` | `LECTEUR` ou `REGULATEUR` |
| `POST /api/v1/hospitals/reservations` | `REGULATEUR` |
| `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Public, sans détails sensibles |
| `GET /actuator/metrics` et ses mesures | `REGULATEUR`, seulement si exposées |
| Autres routes | Refusées |

Spring vérifie signature, expiration, émetteur exact et audience `medhead-api`.
Les rôles sont fournis par un mapper Keycloak dans le claim `roles`. Le contrôle
est effectué dans Spring même si le formulaire est contourné. SvelteKit contrôle
également le rôle avant l’envoi d’une réservation. Une réponse API `401` ou `403`
est affichée comme un refus certain, sans relancer la réservation.

L’API Spring n’accepte pas de session navigateur : elle est stateless et exige
un Bearer. La protection CSRF est donc désactivée uniquement pour cette API ;
les cookies et la protection CSRF restent du côté SvelteKit. Aucun CORS ouvert
n’est nécessaire au parcours web. Une déconnexion locale invalide la session
MedHead ; un JWT déjà délivré reste cependant utilisable directement contre l’API
jusqu’à son expiration. La révocation immédiate de tous les JWT et les notifications
de déconnexion Keycloak vers SvelteKit ne sont pas mises en place.

## Configuration

Les variables initiales figurent dans `.env.example`. Les plus importantes sont :

| Variable | Usage |
| --- | --- |
| `KEYCLOAK_URL` | URL publique de Keycloak, `http://localhost:8180` en local |
| `KEYCLOAK_PORT` | Port publié ; adapter aussi `KEYCLOAK_URL` s’il change |
| `MEDHEAD_PUBLIC_ORIGIN` | Origine exacte du navigateur ; déduite de `FRONTEND_PORT` dans Compose. Aussi compilée dans l’image du front-end pour le contrôle CSRF : reconstruire après une modification |
| `KEYCLOAK_CLIENT_SECRET` | Secret du client confidentiel `medhead-web` |
| `KEYCLOAK_DB_PASSWORD` | Mot de passe de la base dédiée aux identités |
| `KEYCLOAK_ISSUER_URI` | Émetteur attendu, identique au claim `iss` |
| `KEYCLOAK_INTERNAL_ORIGIN` | Accès serveur à Keycloak via DNS Docker, sans changer l’émetteur |
| `KEYCLOAK_JWKS_URI` | URL serveur des clés publiques pour Spring |
| `AUTH_REDIS_URL` | Redis TLS des sessions, `rediss://redis:6379/1` dans Compose |
| `AUTH_REDIS_CA_CERT` | Certificat d’autorité Redis à vérifier |
| `REDIS_PASSWORD` | Authentification auprès de Redis |
| `AUTH_ALLOW_HTTP` | Exception explicite pour la démonstration HTTP locale |

Pour lancer SvelteKit hors Docker, exporter les variables serveur correspondantes,
notamment le secret client, `AUTH_ALLOW_HTTP=true` et les coordonnées du Redis
TLS accessible localement. Redis n’est pas publié par le Compose principal.

Ce Compose utilise Keycloak `start-dev`, des mots de passe de démonstration et
HTTP sur des ports publiés uniquement sur `127.0.0.1`. Une mise en production
demande HTTPS, `AUTH_ALLOW_HTTP=false`, le mode production de Keycloak, ses URL
publiques correctes et des secrets propres à l’environnement. L’import local
configure aussi `sslRequired=none` ; ce réglage doit être adapté en production.

## Vérifications

```bash
cd frontend
npm test
npm run check
npm run build
```

Les tests de session couvrent expiration, rotation, révocation, concurrence,
absence de résurrection après déconnexion, attributs des cookies et refus CSRF.

```bash
./mvnw test
```

Les tests Spring de sécurité utilisent de vrais JWT signés et vérifient `401`,
`403`, permissions de lecture/réservation, signature, émetteur, audience et
expiration. Les tests d’intégration existants conservent leurs indicateurs
d’activation et utilisent les rôles nécessaires. Les tests de charge utilisent
un compte de service dans un Keycloak isolé, décrit dans leur README.

Pour valider le parcours complet avec les conteneurs :

1. Se connecter avec `lecteur` : carte visible, réservation désactivée.
2. Se déconnecter, puis se connecter avec `regulateur` : sélectionner Westminster
   et Cardiologie, puis réserver un lit fictif.
3. Recharger la page : l’identité reste reconnue, sans jeton dans les données HTML.
4. Se déconnecter : la page revient à l’accueil anonyme.
5. Appeler l’API sans Bearer : réponse `401`, tandis que readiness reste publique.
