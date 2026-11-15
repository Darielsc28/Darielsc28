# Scanner de cours

Scanne des feuilles de cours à la volée avec une caméra fixe : la feuille est
détectée, redressée au format A4 et enregistrée en PNG. En mode automatique, il
suffit de changer de feuille — chaque nouvelle page immobile est scannée toute
seule et un bip confirme l'enregistrement.

## Installation (Windows)

1. Installer [Python](https://www.python.org/downloads/) en cochant
   **Add python.exe to PATH**.
2. Double-cliquer sur `lancer.bat`. La première fois, il installe ce qu'il faut.

Sous Linux / macOS : `pip install -r requirements.txt` puis `python scanner.py`.

## Utilisation

1. Fixer la caméra au-dessus du bureau, bien à la verticale de la feuille.
2. Poser la feuille sur un **fond sombre et uni** (sous-main noir, carton,
   tissu). La feuille doit être entièrement visible.
3. Lancer, taper le nom du classeur (ex. `maths`), puis enchaîner les feuilles :
   poser, attendre le bip, remplacer.

Les scans vont dans `Scans_cours/<nom>/<nom>_0001.png`, `_0002.png`… dans le
dossier utilisateur. Relancer avec le même nom continue la numérotation.

| Touche | Action |
| --- | --- |
| Espace | scanner tout de suite |
| A | mode automatique oui / non |
| M | rendu : couleur (fond blanchi), gris, noir et blanc, brut |
| R | tourner les scans de 90° (si la feuille est couchée dans l'image) |
| U | supprimer le dernier scan |
| Q / Échap | quitter |

Le cadre est **orange** tant que la feuille bouge, **vert** quand elle est
stable. Pour un recto-verso : retourner la feuille, elle est rescannée.

## Options

```
lancer.bat --camera 1                 autre webcam
lancer.bat --camera http://192.168.1.20:8080/video   téléphone (appli « IP Webcam »)
lancer.bat --nom physique --dpi 300 --mode nb
lancer.bat --dossier D:\Cours --delai 0.4
```

`python scanner.py --help` liste tout.

## Qualité

La netteté dépend de la caméra : une webcam 1080p donne un texte lisible mais
pas fin ; une webcam 4K ou un téléphone utilisé comme caméra donne un résultat
proche d'un vrai scanner. Un éclairage uniforme, sans reflet, compte autant que
la résolution.
