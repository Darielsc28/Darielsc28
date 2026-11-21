# PubStop

Bloqueur de publicités pour Android (7.0 et plus), sans root.

L'application crée un VPN **local** : seules les requêtes DNS y passent. Les
domaines publicitaires et de pistage (liste [StevenBlack/hosts](https://github.com/StevenBlack/hosts),
environ 76 000 domaines) reçoivent une réponse nulle ; tout le reste est relayé
au DNS habituel du réseau. Aucun trafic n'est envoyé à un serveur tiers.

## Installation

1. Copier `dist/PubStop.apk` sur le téléphone et l'ouvrir.
2. Autoriser l'installation depuis cette source si Android le demande.
3. Ouvrir PubStop, toucher **Activer le blocage** et accepter la demande de VPN.

## Limites

- Les publicités servies depuis le même domaine que le contenu (YouTube,
  Facebook, Instagram…) ne peuvent pas être bloquées par DNS.
- Si le « DNS privé » d'Android pointe vers un fournisseur précis, il contourne
  le blocage : le régler sur « Automatique » ou « Désactivé ». Même chose pour
  l'option « DNS sécurisé » du navigateur si un fournisseur y est choisi.
- Un seul VPN peut être actif à la fois sur Android.

## Compilation

```
./build.sh
```

Nécessite un JDK 17+, Python 3 avec pip et curl (Linux x86-64). Le script
télécharge ses outils dans `.toolchain/`, lance les tests puis produit
`dist/PubStop.apk`. La clé de signature (`pubstop.keystore`) est créée au
premier lancement et n'est pas versionnée : un APK signé avec une autre clé ne
peut pas mettre à jour une installation existante sans désinstallation.
