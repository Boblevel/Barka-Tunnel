# Déploiement final Barka Tunnel

## 1. Android / dépôt GitHub

Décompresser le ZIP final dans le dépôt local `~/storage/downloads/barka-tunnel`, vérifier les diffs, puis ajouter uniquement les chemins indiqués dans le bloc Termux fourni avec la livraison.

Après le push, attendre le build GitHub Actions vert, télécharger l'APK et vérifier les trois réseaux sur téléphone réel.

## 2. Backend / Panel Barka Tunnel sur VPS

Le backend déployé se trouve habituellement dans `/opt/Barka-Tunnel/backend`.

Avant remplacement, sauvegarder au minimum `.env` et `data/`. Ne jamais envoyer ces fichiers dans Git.

Les fichiers serveur à mettre à jour pour cette évolution sont :

- `backend/app/admin_panel.py`
- `backend/app/db.py`
- `backend/app/models.py`
- `backend/app/vpn_profiles.py`

Le redémarrage recommandé si le déploiement utilise le `docker-compose.yml` fourni :

```bash
cd /opt/Barka-Tunnel/backend || exit 1
cp -a .env /root/barka-env-backup-$(date +%Y%m%d-%H%M%S) 2>/dev/null || true
cp -a data /root/barka-data-backup-$(date +%Y%m%d-%H%M%S) 2>/dev/null || true
docker compose up -d --build
curl -fsS http://127.0.0.1:8085/health
```

Le démarrage du backend exécute une migration additive : la colonne `maintenance` est ajoutée à `vpn_profiles` si elle n'existe pas. Les profils et leurs versions existantes sont conservés.

## 3. Utilisation du panel

Dans **Services VPN** :

- modifier seulement les coordonnées réellement nécessaires du profil ;
- laisser le protocole verrouillé par opérateur ;
- cliquer **ENREGISTRER** pour publier une nouvelle version ;
- utiliser **Maintenance** pour rendre uniquement ce réseau indisponible temporairement.

Dans **Codes d'activation**, l'offre **1 semaine** existe déjà et correspond à 7 jours.

## 4. Sécurité

Ne jamais inclure dans un ZIP public : `.env`, bases SQLite réelles, clés privées, tokens administrateur ou secrets de paiement.
