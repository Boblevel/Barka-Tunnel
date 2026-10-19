# Barka Tunnel — 2.0.4.3 / code 113

Base vérifiée : d842587dd62ffd5294a84a374cfce274610df8fd (main, compteur des sous-panels intégré).
Ne plus changer versionName/versionCode sans demande explicite du propriétaire.
Cycle habituel : fichiers ciblés → Termux avec gh déjà authentifié → commit/push main → workflow build-clean.yml → backend et APK signée sur VPS → essai utilisateur. Aucun jeton à transmettre dans le chat.

## Les six changements

1. Version 2.0.4.3 / 113.
2. Import APK jusqu’à 300 MiB (314572800 octets), blocs de 512 Kio, contrôle espace disque, URL immuable par SHA-256 et publication séparée après import. Les fragments abandonnés expirent après 24 h. Téléchargement interne activable dans le panel : progression, pause/reprise conservée après fermeture ou arrêt du processus, vérification identité/version/signature, SHA-256 pour les URL du panel, demande d’installation Android. Quitter l’écran met en pause ; le rouvrir reprend. Annuler l’installation conserve l’APK. Les liens externes doivent supporter HTTP Range pour reprendre ; les liens du panel le supportent. Une limite de 300 Mo n’ajoute pas de stockage physique au VPS : saturation réelle signalée explicitement.
3. IP Finder : suppression 10.193, 10.194 (10.10194 interprété comme faute de saisie) et 10.80, y compris préférences enregistrées. Détection immédiate et lors des changements réseau, rouge/vert, bouton masqué dès que l’IP convient. Retraits antérieurs conservés.
4. SLOWDNS, VLESS, UDP et SSH_TLS assignables aux réseaux ; modèles de configuration, brouillons et validation finale conservés. MOOV FORFAIT TIKTOK immédiatement après Moov, SSH_TLS par défaut, désactivé avant configuration réelle du serveur.
5. Date puis heure UTC sur deux lignes : création abonnements, Redeem, sous-panels et codes revendeurs. UTC correspond au Burkina Faso.
6. Codes revendeurs de toutes durées : durée complète malgré l’échéance future du panel, gel du temps restant à expiration, reprise au renouvellement (y compris payé), sans double comptage. Crédits empilés décalés ; révocations, suppressions et autres abonnements préservés.

## Utilisation des mises à jour

Installer d’abord l’APK 113 signée par le workflow existant. Les anciennes APK ne possèdent pas le nouveau téléchargeur. Dans le panel, importer l’APK, renseigner sa version/code exacts, activer le téléchargement interne, enregistrer la publication. Android demande le consentement utilisateur ; l’APK doit conserver la signature.
Un test complet de remplacement depuis 113 exige une prochaine version autorisée de code supérieur : ne pas incrémenter pour ce seul test sans instruction.

## SSL/TLS → SSH : configuration réelle nécessaire

Il faut un serveur SSH réellement encapsulé dans TLS, par exemple stunnel devant sshd, avec certificat de confiance correspondant au SNI. Ne pas remplacer le HTTPS du panel sur un port occupé. Exemple à adapter, non déployé automatiquement :

```ini
[ssh-tls]
accept = 0.0.0.0:8443
connect = 127.0.0.1:22
cert = /etc/letsencrypt/live/ssh.example.org/fullchain.pem
key = /etc/letsencrypt/live/ssh.example.org/privkey.pem
sslVersionMin = TLSv1.2
```

```json
{"host":"ssh.example.org","port":8443,"sni":"ssh.example.org","username":"COMPTE_SSH","password":"MOT_DE_PASSE_SSH","udpgw_host":"127.0.0.1","udpgw_port":7300}
```

L’UDPGW doit fonctionner côté SSH pour UDP/DNS. Chaîne TLS et nom du certificat sont vérifiés ; allow_insecure est refusé. Remplacer les exemples avant activation. Le nom du forfait ne garantit pas sa compatibilité opérateur : essai réel sur téléphone nécessaire.
Références : https://www.stunnel.org/howto.html ; https://www.stunnel.org/manual.html ; https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()

## Livraison

Le script utilise ~/Barka-113-travail, vérifie base Git et empreintes, refuse les divergences et n’écrase pas les travaux locaux. Il pousse main et suit le workflow existant. Le garde-fou VPS conserve les contrôles de source et le retour arrière backend. Secrets, données et intégrations SasPay conservés ; services.py reçoit uniquement la comptabilisation transactionnelle des pauses.
Les tests locaux ne remplacent pas les essais réels du tunnel, du forfait et de l’installation sur téléphone. Aucun push ou déploiement effectué pendant la préparation du paquet.
