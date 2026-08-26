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

Le démarrage du backend exécute uniquement des migrations additives : `maintenance` dans `vpn_profiles`, `access_disabled` dans `devices`, `applied_from`, `applied_until` et `deleted_at` dans `activation_codes`, puis les nouvelles tables `redeem_codes` et `redeem_usages`. Les lignes existantes, profils, abonnements et versions sont conservés.

## 3. Utilisation du panel

Dans **Services VPN** :

- modifier seulement les coordonnées réellement nécessaires du profil ;
- laisser le protocole verrouillé par opérateur ;
- cliquer **ENREGISTRER** pour publier une nouvelle version ;
- utiliser **Maintenance** pour rendre uniquement ce réseau indisponible temporairement.

Dans **Abonnements**, les accès issus d'un paiement et les abonnements individuels générés depuis le panel sont regroupés. Chaque code d'abonnement est à usage individuel et les offres 24h, 1 semaine (7 jours), 2 semaines et 1 mois sont conservées.

Dans **Codes Redeem gratuits**, choisissez une durée libre en heures, une limite maximale d'utilisateurs et éventuellement plusieurs codes à générer. Le panel affiche le nombre d'utilisations sur la limite. Désactiver bloque les nouvelles activations ; Réactiver les autorise à nouveau ; Supprimer retire le code et le temps restant qu'il a accordé. Les dates visibles utilisent le format `JJ-MM-AAAA`.

## 4. Sécurité

Ne jamais inclure dans un ZIP public : `.env`, bases SQLite réelles, clés privées, tokens administrateur ou secrets de paiement.

## 5. Contrôles Android complémentaires

- `Latence` doit apparaître sans « VPN » dans le Journal.
- Le Guide doit mentionner M.RHAFF et expliquer les trois opérateurs.
- Sur données mobiles, Orange doit exiger l'IP courante validée par IP Finder ; sur Wi-Fi, cette garde opérateur n'est pas appliquée.
- Paramètres > Canal Telegram officiel doit ouvrir `https://t.me/barkaTunnel`.

## 6. Vérifications automatiques Android

Au démarrage et à chaque retour au premier plan avec Internet disponible, Barka Tunnel vérifie automatiquement :

- les versions des profils MOOV-AFRICA / ORANGE / TELECEL et synchronise une nouvelle configuration obligatoire sans attendre un appui sur le bouton de rafraîchissement ;
- la disponibilité d'une nouvelle APK. Une mise à jour marquée obligatoire dans le panel ouvre automatiquement le dialogue bloquant.

Ces vérifications n'altèrent pas les moteurs VPN fonctionnels.
