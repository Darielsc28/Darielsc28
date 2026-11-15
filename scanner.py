#!/usr/bin/env python3
"""Scanner de cours rapide a la camera.

Une camera fixe regarde une feuille posee sur un fond sombre. Le programme
detecte la feuille, la redresse au format A4 et l'enregistre en PNG.
En mode automatique il suffit de changer de feuille : chaque nouvelle page
immobile est scannee toute seule (un bip confirme l'enregistrement).

Touches (fenetre d'apercu active) :
    ESPACE  scanner maintenant          A  automatique oui/non
    M       changer le rendu            R  tourner les scans de 90 degres
    U       supprimer le dernier scan   Q / Echap  quitter
"""
from __future__ import annotations

import argparse
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np

A4_MM = (210.0, 297.0)
MODES = ["couleur", "gris", "nb", "brut"]
NOMS_MODES = {
    "couleur": "couleur (fond blanchi)",
    "gris": "niveaux de gris",
    "nb": "noir et blanc",
    "brut": "brut (sans retouche)",
}


# --------------------------------------------------------------------------
# Detection de la feuille
# --------------------------------------------------------------------------

def ordonner_points(pts: np.ndarray) -> np.ndarray:
    """Ordonne 4 points : haut-gauche, haut-droit, bas-droit, bas-gauche."""
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    s = pts.sum(axis=1)
    d = pts[:, 0] - pts[:, 1]
    return np.array(
        [pts[np.argmin(s)], pts[np.argmax(d)], pts[np.argmax(s)], pts[np.argmin(d)]],
        dtype=np.float32,
    )


def _quadrilatere(contour: np.ndarray) -> np.ndarray | None:
    """Approxime un contour par 4 coins, ou None si ce n'est pas une feuille."""
    enveloppe = cv2.convexHull(contour)
    perimetre = cv2.arcLength(enveloppe, True)
    for eps in (0.02, 0.03, 0.04, 0.06):
        approx = cv2.approxPolyDP(enveloppe, eps * perimetre, True)
        if len(approx) == 4:
            return approx.reshape(4, 2).astype(np.float32)
        if len(approx) < 4:
            break
    rect = cv2.minAreaRect(enveloppe)
    aire_rect = rect[1][0] * rect[1][1]
    if aire_rect > 0 and cv2.contourArea(enveloppe) / aire_rect > 0.9:
        return cv2.boxPoints(rect).astype(np.float32)
    return None


def _meilleur_quad(masque: np.ndarray, aire_min: float) -> np.ndarray | None:
    h, w = masque.shape
    contours, _ = cv2.findContours(masque, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:3]:
        if cv2.contourArea(c) < aire_min:
            break
        quad = _quadrilatere(c)
        if quad is None or cv2.contourArea(quad) < aire_min:
            continue
        # Une feuille coupee par le bord de l'image n'est pas scannable.
        if (quad[:, 0].min() < 2 or quad[:, 1].min() < 2
                or quad[:, 0].max() > w - 3 or quad[:, 1].max() > h - 3):
            continue
        return quad
    return None


def detecter_page(image: np.ndarray) -> np.ndarray | None:
    """Renvoie les 4 coins de la feuille (coordonnees de `image`) ou None."""
    h, w = image.shape[:2]
    echelle = 640.0 / max(h, w)
    petit = cv2.resize(image, None, fx=echelle, fy=echelle, interpolation=cv2.INTER_AREA)
    gris = cv2.cvtColor(petit, cv2.COLOR_BGR2GRAY)
    gris = cv2.GaussianBlur(gris, (5, 5), 0)
    aire_min = 0.12 * gris.shape[0] * gris.shape[1]
    noyau = cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7))

    # 1) Feuille claire sur fond sombre : seuillage automatique.
    _, masque = cv2.threshold(gris, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    masque = cv2.morphologyEx(masque, cv2.MORPH_OPEN, noyau)
    masque = cv2.morphologyEx(masque, cv2.MORPH_CLOSE, noyau)
    quad = _meilleur_quad(masque, aire_min)

    # 2) Secours : contours (fond peu contraste).
    if quad is None:
        bords = cv2.Canny(gris, 40, 120)
        bords = cv2.dilate(bords, noyau)
        plein = np.zeros_like(bords)
        contours, _ = cv2.findContours(bords, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(plein, contours, -1, 255, cv2.FILLED)
        plein = cv2.erode(plein, noyau)
        quad = _meilleur_quad(plein, aire_min)

    if quad is None:
        return None
    return ordonner_points(quad / echelle)


# --------------------------------------------------------------------------
# Redressement A4 et amelioration
# --------------------------------------------------------------------------

def taille_a4(dpi: int) -> tuple[int, int]:
    """(largeur, hauteur) en pixels d'un A4 portrait."""
    return round(A4_MM[0] / 25.4 * dpi), round(A4_MM[1] / 25.4 * dpi)


def redresser(image: np.ndarray, quad: np.ndarray, dpi: int,
              marge: float = 0.006, taille: tuple[int, int] | None = None) -> np.ndarray:
    """Redresse la feuille en A4 (portrait ou paysage selon son orientation)."""
    centre = quad.mean(axis=0)
    quad = centre + (quad - centre) * (1.0 - marge)  # rogne le lisere du bord
    largeur = (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3])) / 2
    hauteur = (np.linalg.norm(quad[3] - quad[0]) + np.linalg.norm(quad[2] - quad[1])) / 2
    court, long_ = taille or taille_a4(dpi)
    w, h = (long_, court) if largeur > hauteur else (court, long_)
    dest = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], dtype=np.float32)
    matrice = cv2.getPerspectiveTransform(quad.astype(np.float32), dest)
    return cv2.warpPerspective(image, matrice, (w, h), flags=cv2.INTER_CUBIC,
                               borderMode=cv2.BORDER_REPLICATE)


def blanchir_fond(image: np.ndarray) -> np.ndarray:
    """Supprime ombres et teinte du papier : le fond devient blanc."""
    h, w = image.shape[:2]
    petit = cv2.resize(image, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA)
    noyau = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    fond = cv2.dilate(petit, noyau)          # efface l'encre
    fond = cv2.medianBlur(fond, 21)
    fond = cv2.resize(fond, (w, h), interpolation=cv2.INTER_LINEAR)
    return cv2.divide(image, fond, scale=255)


def ameliorer(page: np.ndarray, mode: str) -> np.ndarray:
    if mode == "brut":
        return page
    net = blanchir_fond(page)
    if mode == "couleur":
        return net
    gris = cv2.cvtColor(net, cv2.COLOR_BGR2GRAY)
    if mode == "gris":
        return gris
    return cv2.adaptiveThreshold(gris, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                 cv2.THRESH_BINARY, 35, 12)


def tourner(image: np.ndarray, quarts: int) -> np.ndarray:
    codes = {1: cv2.ROTATE_90_CLOCKWISE, 2: cv2.ROTATE_180, 3: cv2.ROTATE_90_COUNTERCLOCKWISE}
    quarts %= 4
    return image if quarts == 0 else cv2.rotate(image, codes[quarts])


# --------------------------------------------------------------------------
# Enregistrement
# --------------------------------------------------------------------------

class Archive:
    """Numerote et enregistre les pages en tache de fond."""

    def __init__(self, dossier: Path, prefixe: str):
        self.dossier = dossier
        self.prefixe = prefixe
        dossier.mkdir(parents=True, exist_ok=True)
        motif = re.compile(re.escape(prefixe) + r"_(\d+)\.png$", re.IGNORECASE)
        numeros = [int(m.group(1)) for f in dossier.iterdir() if (m := motif.match(f.name))]
        self.prochain = max(numeros, default=0) + 1
        self.fichiers: list[Path] = []
        self._pool = ThreadPoolExecutor(max_workers=1)
        self._taches = []

    def ajouter(self, page: np.ndarray, mode: str, quarts: int) -> Path:
        chemin = self.dossier / f"{self.prefixe}_{self.prochain:04d}.png"
        self.prochain += 1
        self.fichiers.append(chemin)

        def travail():
            resultat = tourner(ameliorer(page, mode), quarts)
            ok, tampon = cv2.imencode(".png", resultat, [cv2.IMWRITE_PNG_COMPRESSION, 3])
            if not ok:
                raise RuntimeError(f"encodage PNG impossible : {chemin}")
            chemin.write_bytes(tampon.tobytes())  # gere les chemins accentues

        self._taches.append(self._pool.submit(travail))
        return chemin

    def attendre(self):
        for t in self._taches:
            t.result()
        self._taches.clear()

    def annuler_dernier(self) -> Path | None:
        if not self.fichiers:
            return None
        self.attendre()
        chemin = self.fichiers.pop()
        chemin.unlink(missing_ok=True)
        self.prochain -= 1
        return chemin

    def fermer(self):
        self.attendre()
        self._pool.shutdown()


def bip():
    def jouer():
        try:
            import winsound
            winsound.Beep(1300, 90)
        except Exception:
            print("\a", end="", flush=True)
    threading.Thread(target=jouer, daemon=True).start()


# --------------------------------------------------------------------------
# Camera
# --------------------------------------------------------------------------

def ouvrir_source(source: str, largeur: int, hauteur: int):
    """Ouvre une webcam (numero), un flux reseau (URL) ou un fichier video."""
    if source.isdigit():
        index = int(source)
        cap = (cv2.VideoCapture(index, cv2.CAP_DSHOW) if sys.platform == "win32"
               else cv2.VideoCapture(index))
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, largeur)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, hauteur)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        return cap, True
    est_flux = "://" in source
    return cv2.VideoCapture(source), est_flux


# --------------------------------------------------------------------------
# Boucle principale
# --------------------------------------------------------------------------

class Scanner:
    SEUIL_MOUVEMENT = 4.0     # difference moyenne entre 2 images = quelque chose bouge
    SEUIL_COINS = 0.006       # deplacement max des coins (fraction de la diagonale)
    SEUIL_NOUVELLE_PAGE = 10  # difference de contenu = page differente

    def __init__(self, args, archive: Archive):
        self.args = args
        self.archive = archive
        self.mode = args.mode
        self.quarts = args.rotation // 90
        self.auto = not args.manuel
        self.arme = True              # pret a scanner la prochaine page stable
        self.quad_prec = None
        self.gris_prec = None
        self.stable_depuis = None
        self.perdu_depuis = None
        self.vignette_ref = None
        self.apercu_dernier = None
        self.flash_jusqua = 0.0
        self.message = ""
        self.message_jusqua = 0.0
        self.total = 0

    def dire(self, texte: str, maintenant: float, duree: float = 2.5):
        self.message, self.message_jusqua = texte, maintenant + duree
        print(texte)

    def _vignette(self, image, quad):
        v = redresser(image, quad, 0, taille=(48, 68))
        return cv2.GaussianBlur(cv2.cvtColor(v, cv2.COLOR_BGR2GRAY), (5, 5), 0)

    def capturer(self, image, quad, maintenant: float):
        page = redresser(image, quad, self.args.dpi)
        chemin = self.archive.ajouter(page, self.mode, self.quarts)
        self.total += 1
        self.arme = False
        self.vignette_ref = self._vignette(image, quad)
        self.apercu_dernier = tourner(cv2.resize(page, None, fx=0.12, fy=0.12,
                                                 interpolation=cv2.INTER_AREA), self.quarts)
        self.flash_jusqua = maintenant + 0.15
        self.dire(f"Enregistre : {chemin.name}", maintenant)
        bip()

    def traiter(self, image, maintenant: float):
        """Analyse une image ; renvoie (quad, stable)."""
        h, w = image.shape[:2]
        petit = cv2.cvtColor(cv2.resize(image, (160, 120), interpolation=cv2.INTER_AREA),
                             cv2.COLOR_BGR2GRAY)
        mouvement = 0.0 if self.gris_prec is None else float(cv2.absdiff(petit, self.gris_prec).mean())
        self.gris_prec = petit
        if mouvement > self.SEUIL_MOUVEMENT:
            self.arme = True  # une main est passee : la page a sans doute change

        quad = detecter_page(image)
        if quad is None:
            self.quad_prec, self.stable_depuis = None, None
            self.perdu_depuis = self.perdu_depuis or maintenant
            if maintenant - self.perdu_depuis > 0.3:
                self.arme = True
            return None, False
        self.perdu_depuis = None

        immobile = (self.quad_prec is not None
                    and mouvement <= self.SEUIL_MOUVEMENT
                    and np.abs(quad - self.quad_prec).max() < self.SEUIL_COINS * np.hypot(w, h))
        self.quad_prec = quad
        if not immobile:
            self.stable_depuis = None
            return quad, False
        if self.stable_depuis is None:
            self.stable_depuis = maintenant
        stable = maintenant - self.stable_depuis >= self.args.delai

        if stable and self.auto:
            if not self.arme and self.vignette_ref is not None:
                ecart = float(cv2.absdiff(self._vignette(image, quad), self.vignette_ref).mean())
                if ecart > self.SEUIL_NOUVELLE_PAGE:
                    self.arme = True  # page remplacee tres doucement
            if self.arme:
                self.capturer(image, quad, maintenant)
        return quad, stable

    # ---- affichage ----

    def dessiner(self, image, quad, stable, maintenant: float):
        h, w = image.shape[:2]
        k = min(1280 / w, 720 / h, 1.0)
        vue = cv2.resize(image, None, fx=k, fy=k, interpolation=cv2.INTER_AREA) if k < 1 else image.copy()
        if quad is not None:
            couleur = (0, 200, 0) if stable else (0, 165, 255)
            cv2.polylines(vue, [np.int32(quad * k)], True, couleur, 3, cv2.LINE_AA)
        if maintenant < self.flash_jusqua:
            vue = cv2.addWeighted(vue, 0.4, np.full_like(vue, 255), 0.6, 0)
        if self.apercu_dernier is not None:
            a = self.apercu_dernier if self.apercu_dernier.ndim == 3 else cv2.cvtColor(self.apercu_dernier, cv2.COLOR_GRAY2BGR)
            ah, aw = a.shape[:2]
            if ah + 20 < vue.shape[0] and aw + 20 < vue.shape[1]:
                vue[10:10 + ah, -10 - aw:-10] = a
                cv2.rectangle(vue, (vue.shape[1] - 11 - aw, 9), (vue.shape[1] - 10, 10 + ah), (255, 255, 255), 1)

        if quad is None:
            etat, c = "Feuille non detectee (fond sombre, feuille entiere dans l'image)", (0, 0, 255)
        elif not self.auto:
            etat, c = "ESPACE pour scanner", (0, 200, 0)
        elif not self.arme:
            etat, c = "OK - changez de page", (0, 200, 0)
        else:
            etat, c = "Ne bougez plus...", (0, 165, 255)
        lignes = [
            (etat, c),
            (f"Pages: {self.total}   Auto: {'OUI' if self.auto else 'NON'}   Rendu: {self.mode}"
             f"   Rotation: {self.quarts * 90}", (255, 255, 255)),
            ("ESPACE scanner  A auto  M rendu  R rotation  U annuler  Q quitter", (200, 200, 200)),
        ]
        if maintenant < self.message_jusqua:
            lignes.insert(1, (self.message, (255, 255, 0)))
        cv2.rectangle(vue, (0, 0), (min(vue.shape[1], 760), 14 + 26 * len(lignes)), (0, 0, 0), cv2.FILLED)
        for i, (texte, coul) in enumerate(lignes):
            cv2.putText(vue, texte, (10, 26 + 26 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.6, coul, 1, cv2.LINE_AA)
        return vue

    def touche(self, code: int, image, quad, maintenant: float) -> bool:
        """Gere une touche ; renvoie False pour quitter."""
        if code in (ord("q"), ord("Q"), 27):
            return False
        if code == ord(" "):
            if quad is None:
                self.dire("Impossible : feuille non detectee", maintenant)
            else:
                self.capturer(image, quad, maintenant)
        elif code in (ord("a"), ord("A")):
            self.auto = not self.auto
            self.arme = False  # ne rescanne pas la page deja en place
            self.dire(f"Mode automatique : {'OUI' if self.auto else 'NON'}", maintenant)
        elif code in (ord("m"), ord("M")):
            self.mode = MODES[(MODES.index(self.mode) + 1) % len(MODES)]
            self.dire(f"Rendu : {NOMS_MODES[self.mode]}", maintenant)
        elif code in (ord("r"), ord("R")):
            self.quarts = (self.quarts + 1) % 4
            self.dire(f"Rotation des scans : {self.quarts * 90} degres", maintenant)
        elif code in (ord("u"), ord("U")):
            supprime = self.archive.annuler_dernier()
            if supprime is None:
                self.dire("Rien a annuler", maintenant)
            else:
                self.total -= 1
                self.apercu_dernier = None
                self.dire(f"Supprime : {supprime.name}", maintenant)
        return True


def nettoyer_nom(nom: str) -> str:
    nom = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", nom).strip(" .")
    return nom or "page"


def lire_arguments():
    p = argparse.ArgumentParser(description="Scanner de cours rapide a la camera (A4, PNG).")
    p.add_argument("--camera", default="0",
                   help="numero de webcam (0, 1...), URL d'un flux (telephone) ou fichier video")
    p.add_argument("--dossier", type=Path, default=Path.home() / "Scans_cours",
                   help="dossier ou ranger les scans (defaut : %(default)s)")
    p.add_argument("--nom", help="nom du classeur / de la matiere (sous-dossier et prefixe des fichiers)")
    p.add_argument("--dpi", type=int, default=200, help="resolution du A4 produit (defaut : 200)")
    p.add_argument("--mode", choices=MODES, default="couleur", help="rendu des pages")
    p.add_argument("--delai", type=float, default=0.6,
                   help="secondes d'immobilite avant le scan automatique (defaut : 0.6)")
    p.add_argument("--rotation", type=int, choices=[0, 90, 180, 270], default=0,
                   help="rotation appliquee aux scans")
    p.add_argument("--manuel", action="store_true", help="demarrer sans le scan automatique")
    p.add_argument("--largeur", type=int, default=3840, help="largeur demandee a la webcam")
    p.add_argument("--hauteur", type=int, default=2160, help="hauteur demandee a la webcam")
    p.add_argument("--sans-affichage", action="store_true", help=argparse.SUPPRESS)
    return p.parse_args()


def main() -> int:
    args = lire_arguments()
    if args.nom is None:
        args.nom = input("Nom du classeur / de la matiere (ex: maths) : ") if sys.stdin.isatty() else ""
    args.nom = nettoyer_nom(args.nom)

    cap, en_direct = ouvrir_source(args.camera, args.largeur, args.hauteur)
    if not cap.isOpened():
        print(f"Impossible d'ouvrir la camera '{args.camera}'. Essayez --camera 1 "
              "ou verifiez qu'aucune autre application ne l'utilise.")
        return 1
    ips = cap.get(cv2.CAP_PROP_FPS) or 30.0
    archive = Archive(args.dossier / args.nom, args.nom)
    scanner = Scanner(args, archive)
    print(f"Camera : {int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))}x{int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))}")
    print(f"Scans enregistres dans : {archive.dossier}")

    fenetre = "Scanner de cours"
    if not args.sans_affichage:
        cv2.namedWindow(fenetre, cv2.WINDOW_NORMAL)
    echecs, n = 0, 0
    try:
        while True:
            ok, image = cap.read()
            if not ok:
                echecs += 1
                if not en_direct or echecs > 50:
                    if en_direct:
                        print("Flux camera perdu.")
                    break
                time.sleep(0.05)
                continue
            echecs = 0
            n += 1
            # Fichier video : l'horloge suit la video, pas la vitesse de calcul.
            maintenant = time.monotonic() if en_direct else n / ips
            quad, stable = scanner.traiter(image, maintenant)
            if args.sans_affichage:
                continue
            cv2.imshow(fenetre, scanner.dessiner(image, quad, stable, maintenant))
            code = cv2.waitKey(1) & 0xFF
            if code != 255 and not scanner.touche(code, image, quad, maintenant):
                break
            if cv2.getWindowProperty(fenetre, cv2.WND_PROP_VISIBLE) < 1:
                break
    except KeyboardInterrupt:
        pass
    finally:
        cap.release()
        archive.fermer()
        if not args.sans_affichage:
            cv2.destroyAllWindows()
    print(f"Termine : {scanner.total} page(s) dans {archive.dossier}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
