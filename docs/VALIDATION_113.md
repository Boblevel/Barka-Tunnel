# Validation Barka Tunnel 2.0.4.3 / 113

Validation terminée le 19 octobre 2026, sur la base Git d842587dd62ffd5294a84a374cfce274610df8fd.

## Résultats

- Backend : `python -m pytest -q` — 164 réussites, aucun échec. Un avertissement de dépréciation de dépendance.
- Android : `:app:testDebugUnitTest` avec Gradle 8.9, JDK 17 et SDK 35 — BUILD SUCCESSFUL. 61 tests réussis, aucun échec, aucune erreur, aucun test ignoré. Kotlin et Java compilés.
- Contrôles statiques : syntaxe Python, JavaScript des deux panels, XML et `git diff --check` réussis.
- Livraison : intégrité ZIP et empreinte SHA-256 de chaque fichier vérifiées.
- Script Termux : copie, commit, push, relance et refus des modifications locales testés précédemment avec un dépôt Git local et un substitut de `gh`. Aucun push GitHub réel pendant la préparation.

## Couverture ciblée

Reprise du téléchargement APK, remplacement du fichier distant, rejet des plages incohérentes ; passage du dialogue de mise à jour obligatoire au téléchargement interne ; suppression des anciens préfixes IP et affichage rouge/vert avec disparition du bouton ; configuration SSH_TLS ; import APK par fragments et limite de 300 MiB ; gel et reprise de toutes les durées revendeurs, crédits empilés, renouvellements payés, suppressions et absence de double prolongation.

## Mise en service

L'APK signée et le déploiement backend seront produits par le workflow GitHub existant après exécution de la commande Termux. Les avertissements de dépréciation ne bloquent pas la compilation actuelle.

Restent les essais réels sur téléphone : installation de l'APK signée, mise à jour depuis une version compatible et tunnel avec le serveur SSL/TLS→SSH configuré. Le profil MOOV FORFAIT TIKTOK doit recevoir les vrais paramètres du serveur avant activation. Aucun serveur SSH/TLS n'a été configuré à distance pendant cette livraison.

Version verrouillée : ne modifier ni 2.0.4.3 ni 113 sans demande explicite du propriétaire. Les détails de reprise et d'utilisation se trouvent dans REPRISE_113.md.
