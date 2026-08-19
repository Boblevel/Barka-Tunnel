# Barka Tunnel Backend — Étape 6

Backend serveur pour gérer l'accès à Barka Tunnel sans compte utilisateur.

## Ce qui est déjà implémenté

- essai gratuit **1 heure**, démarré uniquement à la demande ;
- temps calculé **uniquement côté serveur** ;
- un même `device_id` ne peut pas réinitialiser son essai en relançant l'API ;
- vérification d'accès `TRIAL / SUBSCRIPTION / NONE` ;
- plans :
  - 24h — 300 XOF
  - 1 semaine — 800 XOF
  - 2 semaines — 1 000 XOF
  - 1 mois — 2 000 XOF
- création de paiement LomoPay côté serveur ;
- URL de checkout renvoyée à l'application ;
- webhook LomoPay signé HMAC-SHA256 ;
- traitement idempotent des webhooks ;
- génération automatique d'un code `BARKA-XXXX-XXXX-XXXX` après paiement confirmé ;
- activation d'un code sur un appareil ;
- code à usage unique ;
- prolongation d'un abonnement actif lorsqu'un nouveau code est activé ;
- génération manuelle de codes via une route administrateur ;
- SQLite en mode WAL pour un premier déploiement simple.

## Sécurité importante

La clé secrète LomoPay reste uniquement dans `backend/.env` sur le VPS.
Elle ne doit jamais être placée dans l'application Android.

Le backend protège le temps d'essai contre la modification de l'horloge du téléphone.
La résistance à une falsification volontaire de l'identifiant d'appareil sera renforcée
plus tard avec Play Integrity. Pour l'étape Android, utilisez un identifiant stable
dérivé de `Settings.Secure.ANDROID_ID` avec la même signature d'application.

## Installation rapide avec Docker

```bash
cd backend
cp .env.example .env
bash scripts/setup_env.sh
nano .env
docker compose up -d --build
curl http://127.0.0.1:8085/health
```

Dans `.env`, renseigner obligatoirement :

```env
PUBLIC_BASE_URL=https://api.votre-domaine.com
LOMOPAY_PUBLIC_KEY=pk_...
LOMOPAY_SECRET_KEY=sk_...
```

Le backend écoute seulement sur `127.0.0.1:8085` dans Docker Compose.
Placez Nginx, Caddy ou votre reverse proxy devant lui pour fournir HTTPS.

## Endpoints Android

### Plans
`GET /v1/plans`

### Démarrer l'essai
`POST /v1/trial/start`

```json
{"device_id":"IDENTIFIANT_STABLE"}
```

### Vérifier l'accès
`POST /v1/access/check`

```json
{"device_id":"IDENTIFIANT_STABLE"}
```

### Créer un paiement
`POST /v1/payments/start`

```json
{
  "device_id":"IDENTIFIANT_STABLE",
  "plan_id":"1w"
}
```

Le montant n'est jamais accepté depuis l'APK : il est choisi côté serveur à partir du plan.

### Vérifier le paiement
`POST /v1/payments/status`

```json
{
  "device_id":"IDENTIFIANT_STABLE",
  "payment_reference":"BT-...",
  "sync_provider":true
}
```

Après confirmation, `activation_code` contient le code à afficher/coller dans l'app.

### Activer un code
`POST /v1/activation/redeem`

```json
{
  "device_id":"IDENTIFIANT_STABLE",
  "code":"BARKA-XXXX-XXXX-XXXX"
}
```

### Webhook LomoPay
`POST /v1/webhooks/lomopay`

La signature `X-LomoPay-Signature` est vérifiée avant tout traitement.

## Génération manuelle de codes

```bash
curl -X POST https://api.votre-domaine.com/v1/admin/codes \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Token: VOTRE_ADMIN_TOKEN' \
  -d '{"plan_id":"1w","count":5}'
```

## Tests

```bash
cd backend
python -m pytest -q
```

Ou, serveur lancé :

```bash
bash scripts/smoke_test.sh http://127.0.0.1:8085
```

## Étape suivante

Raccorder l'application Android à ces routes :
1. `1H` -> `/v1/trial/start`
2. compteur -> `/v1/access/check`
3. `PAYER` -> `/v1/payments/start`
4. navigateur/Custom Tab -> `checkout_url`
5. reprise de l'app -> `/v1/payments/status`
6. `ACTIVER` -> `/v1/activation/redeem`
