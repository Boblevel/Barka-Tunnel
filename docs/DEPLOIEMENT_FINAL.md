# Déploiement final Barka Tunnel

## 1. Android / dépôt GitHub

Décompresser le ZIP final dans le dépôt local `~/storage/downloads/barka-tunnel`, vérifier les diffs, puis ajouter uniquement les chemins indiqués dans le bloc Termux fourni avec la livraison.

Après le push, attendre le build GitHub Actions vert, télécharger l'APK et vérifier les trois réseaux sur téléphone réel.

## 2. Backend / Panel Barka Tunnel sur VPS

Le backend déployé se trouve habituellement dans `/opt/Barka-Tunnel/backend`.

Avant remplacement, sauvegarder au minimum `.env` et `data/`. Ne jamais envoyer ces fichiers dans Git.

Les fichiers serveur à mettre à jour pour cette évolution sont :

- `backend/app/admin_panel.py`
- `backend/app/admin_ops.py`
- `backend/app/db.py`
- `backend/app/main.py`
- `backend/app/models.py`
- `backend/app/services.py`
- `backend/app/vpn_profiles.py`

Le redémarrage recommandé si le déploiement utilise le `docker-compose.yml` fourni :

```bash
cd /opt/Barka-Tunnel/backend || exit 1
cp -a .env /root/barka-env-backup-$(date +%Y%m%d-%H%M%S) 2>/dev/null || true
cp -a data /root/barka-data-backup-$(date +%Y%m%d-%H%M%S) 2>/dev/null || true
docker compose up -d --build
curl -fsS http://127.0.0.1:8085/health
```

Le démarrage du backend exécute uniquement des migrations additives : `maintenance` dans `vpn_profiles`, `access_disabled` dans `devices`, puis `applied_from`, `applied_until` et `deleted_at` dans `activation_codes` si ces colonnes n'existent pas. Les lignes existantes, profils et versions sont conservés.

## 3. Utilisation du panel

Dans **Services VPN** :

- modifier seulement les coordonnées réellement nécessaires du profil ;
- laisser le protocole verrouillé par opérateur ;
- cliquer **ENREGISTRER** pour publier une nouvelle version ;
- utiliser **Maintenance** pour rendre uniquement ce réseau indisponible temporairement.

Dans **Abonnements**, les accès issus d'un paiement sont séparés des codes manuels. Dans **Codes Redeem**, l'offre **1 semaine** existe déjà et correspond à 7 jours. Les boutons Désactiver, Réactiver et Supprimer agissent côté backend ; Supprimer retire le temps restant attribuable au code. Les dates visibles utilisent le format `JJ-MM-AAAA`.

## 4. Sécurité

Ne jamais inclure dans un ZIP public : `.env`, bases SQLite réelles, clés privées, tokens administrateur ou secrets de paiement.

## 5. Contrôles Android complémentaires

- `Latence` doit apparaître sans « VPN » dans le Journal.
- Le Guide doit mentionner M.RHAFF et expliquer les trois opérateurs.
- Sur données mobiles, Orange doit exiger l'IP courante validée par IP Finder ; sur Wi-Fi, cette garde opérateur n'est pas appliquée.
- Paramètres > Canal Telegram officiel doit ouvrir `https://t.me/barkaTunnel`.
