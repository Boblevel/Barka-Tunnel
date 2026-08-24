BARKA TUNNEL C6 — LOT MULTIPROTOCOLE

Ce lot branche le catalogue dynamique existant sur un vrai VpnService Android :
- ORANGE : VLESS WebSocket/TLS via Xray -> SOCKS local -> tun2socks
- MOOV : DNSTT -> SSH -> SOCKS local -> tun2socks
- TELECEL : SSH -> SOCKS local -> tun2socks + UDPGW

Les identifiants/profils ne sont pas codés en dur dans l'APK : ils restent fournis par le backend et parsés en mémoire.
Les logs applicatifs n'écrivent pas les mots de passe, UUID ou clés.

IMPORTANT : la compilation finale et la validation réelle des trois opérateurs doivent être faites par GitHub Actions puis sur téléphone. Un build vert valide la compilation/packaging, pas la connectivité opérateur à lui seul.
