#!/usr/bin/env python3
"""Oreille du pont Pixel — reçoit les résultats du banc d'essai
et, depuis la mécanique d'appel (2026-11-16), les clips audio des
épisodes pertinents d'un appel.

Service minimal (bibliothèque standard seulement) :
- POST /pont/<jeton>/run : l'app envoie un JSON par run ; chaque
  résultat est copié dans runs/<horodatage>_<cas>.json et appendé
  à runs.jsonl ; etat.json tient le compteur que le guet de Muse
  consulte pour savoir qu'un nouveau résultat est arrivé.
- POST /pont/<jeton>/audio/<appel>/<fichier>.wav : le corps brut
  est un clip WAV (un épisode d'appel, jamais le flux entier),
  rangé dans appels/<appel>/. Ne touche pas au compteur : c'est
  le POST /run de l'appel qui déclenche le guet.
- POST /pont/<jeton>/scan/<scan>/<fichier> (.jpg ou .pdf) : le
  corps brut est une pièce d'un scan de l'onglet Scanner de
  Gestion Android (V2, 2026-11-17) — les pages JPEG pleine
  résolution puis le PDF de la session — rangée dans
  scans/<scan>/. Ne touche pas au compteur : c'est le POST
  /run du cas « scan » qui déclenche le guet ; son champ
  donnees.scan nomme le dossier des pièces, comme
  donnees.appel nomme celui des clips d'un appel, et
  donnees.usage porte l'étiquette choisie à l'envoi
  (« livre », « reçu » ou « autre »).
- POST /pont/<jeton>/telemetrie/<session>/<fichier>.jsonl : le
  corps brut est un log de télémétrie de l'accordeur de
  Gestion Android (0.14, 2026-11-18) — JSONL : en-tête,
  échantillons du traqueur et canal d'événements — rangé dans
  telemetrie/<session>/. Ne touche pas au compteur : c'est le
  POST /run du cas « telemetrie » qui déclenche le guet (et
  la sonnette) ; son champ donnees.session nomme le dossier
  du log et donnees.fichier le fichier. Le log voyage comme
  fichier, jamais dans le run (plafond de 512 Ko).
- GET /pont/<jeton>/audio/<appel>/<fichier>.wav : sert le clip,
  pour que Grégoire puisse réécouter le bout pertinent quand ni
  Whisper ni Muse n'ont été concluants.
- Back door de relecture (2026-11-16, design de Grégoire) : le
  gros modèle Whisper vit sur le Pixel, en cage. Quand l'analyse
  de Muse flanche sur un passage précis, elle dépose une
  question ; le téléphone la ramasse à son rythme, décode juste
  ce passage avec son modèle lourd et renvoie la lecture.
  - POST /pont/<jeton>/question : {question_id, appel, episode,
    debut_s, fin_s (temps RELATIFS au clip de l'épisode), doute,
    contexte} -> rangée dans questions/<question_id>.json.
    Ne touche pas au compteur.
  - GET /pont/<jeton>/questions : les questions en attente.
  - POST /pont/<jeton>/reponse : {question_id, texte_lourd} ->
    la question passe à « repondue » ET un run « relecture-
    reponse » est écrit (le guet se réveille, Muse intègre).
- Sonnette courriel (2026-11-17, design de Grégoire) : après
  chaque run écrit (fichier + compteur incrémenté), un thread
  détaché envoie un courriel « fetch <cas> » à Muse
  (PONT_MAIL_DEST, activé seulement si PONT_MAIL_ON=1 dans
  l'environnement) — juste le nom du fichier de run, le
  compteur et l'horodatage de réception, pour qu'elle sache
  quoi aller chercher sans sonder au hasard. Le guet reste en
  place comme filet. Livraison SMTP directe vers les MX du
  domaine destinataire (aucun MTA local sur ce serveur) ; un
  échec est consigné au journal du service et ne bloque
  jamais la réponse au téléphone.
Aucune autre route, aucune écriture ailleurs.
"""
import email.utils
import json
import os
import re
import smtplib
import socket
import struct
import sys
import threading
from datetime import datetime, timezone
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

JETON = os.environ.get("PONT_JETON", "")
BASE = Path(os.environ.get("PONT_DIR", "/var/lib/pont"))
PORT = int(os.environ.get("PONT_PORT", "8100"))
TAILLE_MAX = 512 * 1024
TAILLE_MAX_AUDIO = 12 * 1024 * 1024
TAILLE_MAX_SCAN = 64 * 1024 * 1024
# Log de télémétrie du tuner (0.14) : JSONL à ~12 échantillons
# par seconde, sessions de quelques minutes — 16 Mo laissent
# plus d'une demi-heure de log, largement au-delà de l'usage.
TAILLE_MAX_TELEMETRIE = 16 * 1024 * 1024
MOTIF_NOM = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
MAIL_DEST = os.environ.get("PONT_MAIL_DEST", "").strip()
MAIL_ON = os.environ.get("PONT_MAIL_ON", "") == "1"
MAIL_EXPEDITEUR = "pont@gregoirepainchaud.ca"


def morceau_audio(chemin):
    """Retourne (appel, fichier) si le chemin est une route audio
    valide pour le jeton courant, sinon None."""
    parties = chemin.split("?")[0].split("/")
    # ['', 'pont', jeton, 'audio', appel, fichier]
    if len(parties) != 6 or parties[1] != "pont" or parties[3] != "audio":
        return None
    if not JETON or parties[2] != JETON:
        return None
    appel, fichier = parties[4], parties[5]
    if not MOTIF_NOM.match(appel) or not MOTIF_NOM.match(fichier):
        return None
    if ".." in appel or ".." in fichier or not fichier.endswith(".wav"):
        return None
    return appel, fichier


def morceau_scan(chemin):
    """Retourne (scan, fichier) si le chemin est une route scan
    valide pour le jeton courant, sinon None. Même forme que
    morceau_audio, extensions .jpg et .pdf admises."""
    parties = chemin.split("?")[0].split("/")
    # ['', 'pont', jeton, 'scan', scan, fichier]
    if len(parties) != 6 or parties[1] != "pont" or parties[3] != "scan":
        return None
    if not JETON or parties[2] != JETON:
        return None
    scan, fichier = parties[4], parties[5]
    if not MOTIF_NOM.match(scan) or not MOTIF_NOM.match(fichier):
        return None
    if ".." in scan or ".." in fichier:
        return None
    if not (fichier.endswith(".jpg") or fichier.endswith(".pdf")):
        return None
    return scan, fichier


def morceau_telemetrie(chemin):
    """Retourne (session, fichier) si le chemin est une route
    telemetrie valide pour le jeton courant, sinon None. Même
    forme que morceau_scan, extension .jsonl seulement (le log
    de l'accordeur, 0.14)."""
    parties = chemin.split("?")[0].split("/")
    # ['', 'pont', jeton, 'telemetrie', session, fichier]
    if len(parties) != 6 or parties[1] != "pont" or parties[3] != "telemetrie":
        return None
    if not JETON or parties[2] != JETON:
        return None
    session, fichier = parties[4], parties[5]
    if not MOTIF_NOM.match(session) or not MOTIF_NOM.match(fichier):
        return None
    if ".." in session or ".." in fichier:
        return None
    if not fichier.endswith(".jsonl"):
        return None
    return session, fichier


def _lire_nom_dns(donnees, pos):
    """Lit un nom de domaine (compression comprise) dans une
    réponse DNS. Retourne (nom, position après le nom dans le
    flux d'origine)."""
    morceaux = []
    suite = None
    while True:
        longueur = donnees[pos]
        if longueur == 0:
            pos += 1
            if suite is None:
                suite = pos
            break
        if longueur & 0xC0 == 0xC0:
            pointeur = struct.unpack(">H", donnees[pos:pos + 2])[0] & 0x3FFF
            if suite is None:
                suite = pos + 2
            pos = pointeur
            continue
        morceaux.append(
            donnees[pos + 1:pos + 1 + longueur].decode("ascii", "replace")
        )
        pos += 1 + longueur
    return ".".join(morceaux), (suite if suite is not None else pos)


def _resoudre_mx(domaine):
    """Résout les MX d'un domaine par une requête DNS/UDP
    (bibliothèque standard seulement). Retourne les hôtes par
    préférence croissante ; repli sur le domaine lui-même
    (MX implicite) si la résolution échoue ou ne donne rien."""
    try:
        serveur = None
        for ligne in Path("/etc/resolv.conf").read_text(
            encoding="utf-8"
        ).splitlines():
            champs = ligne.split()
            if len(champs) >= 2 and champs[0] == "nameserver":
                serveur = champs[1]
                break
        if not serveur:
            return [domaine]
        question_nom = b"".join(
            bytes([len(morceau)]) + morceau.encode("ascii")
            for morceau in domaine.split(".")
        ) + b"\x00"
        requete = struct.pack(">HHHHHH", 0x50DB, 0x0100, 1, 0, 0, 0)
        requete += question_nom + struct.pack(">HH", 15, 1)
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(5)
        try:
            sock.sendto(requete, (serveur, 53))
            reponse, _ = sock.recvfrom(4096)
        finally:
            sock.close()
        nb_reponses = struct.unpack(">H", reponse[6:8])[0]
        pos = 12
        _, pos = _lire_nom_dns(reponse, pos)
        pos += 4
        mx = []
        for _ in range(nb_reponses):
            _, pos = _lire_nom_dns(reponse, pos)
            type_rr, _classe, _ttl, longueur = struct.unpack(
                ">HHIH", reponse[pos:pos + 10]
            )
            pos += 10
            debut_rdata = pos
            if type_rr == 15 and longueur >= 3:
                preference = struct.unpack(">H", reponse[pos:pos + 2])[0]
                hote, _ = _lire_nom_dns(reponse, pos + 2)
                mx.append((preference, hote))
            pos = debut_rdata + longueur
        mx.sort()
        hotes = [hote for _pref, hote in mx if hote]
        return hotes or [domaine]
    except Exception:
        return [domaine]


def envoyer_sonnette(cas, nom_fichier, compte, recu_le):
    """Sonnette courriel : un message « fetch <cas> » par run
    reçu. Le corps ne contient que le nom du fichier de run, le
    compteur et l'horodatage de réception — aucune donnée du
    run, aucun jeton. Livraison SMTP directe vers les MX du
    domaine destinataire, hôtes essayés par préférence."""
    dest = MAIL_DEST
    domaine = dest.rsplit("@", 1)[-1]
    message = EmailMessage()
    message["From"] = MAIL_EXPEDITEUR
    message["To"] = dest
    message["Subject"] = f"fetch {cas}"
    message["Date"] = email.utils.formatdate(localtime=True)
    message["Message-ID"] = email.utils.make_msgid(
        domain="gregoirepainchaud.ca"
    )
    message.set_content(
        f"fichier: {nom_fichier}\n"
        f"compteur: {compte}\n"
        f"recu_le: {recu_le}\n"
    )
    erreurs = []
    for hote in _resoudre_mx(domaine):
        try:
            with smtplib.SMTP(hote, 25, timeout=15) as session:
                session.send_message(message)
            print(
                f"PONT-MAIL OK cas={cas} fichier={nom_fichier} via={hote}",
                flush=True,
            )
            return
        except Exception as e:
            erreurs.append(f"{hote}: {type(e).__name__} {e}")
    print(
        f"PONT-MAIL ECHEC cas={cas} fichier={nom_fichier} :: "
        + " ; ".join(erreurs),
        file=sys.stderr,
        flush=True,
    )


def _sonnette_en_thread(cas, nom_fichier, compte, recu_le):
    """Emballage du thread de sonnette : fire-and-forget total,
    aucune exception ne doit jamais en sortir."""
    try:
        envoyer_sonnette(cas, nom_fichier, compte, recu_le)
    except Exception as e:
        print(
            f"PONT-MAIL ECHEC inattendu cas={cas} : "
            f"{type(e).__name__} {e}",
            file=sys.stderr,
            flush=True,
        )


def ecrire_run(charge):
    """Persiste un résultat : fichier dans runs/, ligne dans
    runs.jsonl, compteur du guet incrémenté. Retourne le nom du
    fichier créé."""
    charge["_recu_le"] = datetime.now(timezone.utc).astimezone().isoformat(
        timespec="seconds"
    )
    cas = str(charge.get("cas", "sans-cas")).replace("/", "_")[:64]
    horo = datetime.now().strftime("%Y%m%d-%H%M%S")
    (BASE / "runs").mkdir(parents=True, exist_ok=True)
    fichier = BASE / "runs" / f"{horo}_{cas}.json"
    fichier.write_text(
        json.dumps(charge, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    with open(BASE / "runs.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(charge, ensure_ascii=False) + "\n")
    etat_path = BASE / "etat.json"
    try:
        etat = json.loads(etat_path.read_text(encoding="utf-8"))
    except Exception:
        etat = {"compte": 0}
    etat["compte"] = int(etat.get("compte", 0)) + 1
    etat["dernier"] = charge["_recu_le"]
    etat["dernier_cas"] = cas
    etat["dernier_fichier"] = fichier.name
    etat_path.write_text(json.dumps(etat, ensure_ascii=False), encoding="utf-8")
    # Sonnette courriel : le run est écrit et le compteur a
    # bougé, on prévient Muse par courriel dans un thread
    # détaché — jamais bloquant, jamais fatal pour la réponse.
    if MAIL_ON and MAIL_DEST:
        threading.Thread(
            target=_sonnette_en_thread,
            args=(cas, fichier.name, etat["compte"], charge["_recu_le"]),
            daemon=True,
        ).start()
    return fichier.name


def route_simple(chemin, action):
    """Vrai si le chemin est /pont/<jeton>/<action> pour le jeton
    courant."""
    parties = chemin.split("?")[0].split("/")
    return (
        len(parties) == 4
        and parties[1] == "pont"
        and bool(JETON)
        and parties[2] == JETON
        and parties[3] == action
    )


class Mains(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _lire_json(self):
        """Lit le corps JSON de la requête (taille bornée).
        Retourne le dict, ou None après avoir répondu une erreur."""
        try:
            taille = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            taille = 0
        if taille <= 0 or taille > TAILLE_MAX:
            self.send_response(413)
            self.end_headers()
            return None
        brut = self.rfile.read(taille)
        try:
            charge = json.loads(brut.decode("utf-8"))
        except Exception:
            self.send_response(400)
            self.end_headers()
            return None
        if not isinstance(charge, dict):
            self.send_response(400)
            self.end_headers()
            return None
        return charge

    def _repondre_json(self, code, objet):
        corps = json.dumps(objet, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def do_POST(self):
        audio = morceau_audio(self.path)
        if audio is not None:
            self._recevoir_audio(*audio)
            return
        scan = morceau_scan(self.path)
        if scan is not None:
            self._recevoir_scan(*scan)
            return
        telemetrie = morceau_telemetrie(self.path)
        if telemetrie is not None:
            self._recevoir_telemetrie(*telemetrie)
            return
        if route_simple(self.path, "question"):
            self._recevoir_question()
            return
        if route_simple(self.path, "reponse"):
            self._recevoir_reponse()
            return
        if not route_simple(self.path, "run"):
            self.send_response(404)
            self.end_headers()
            return
        charge = self._lire_json()
        if charge is None:
            return
        ecrire_run(charge)
        self._repondre_json(201, {"ok": True})

    def _recevoir_question(self):
        charge = self._lire_json()
        if charge is None:
            return
        question_id = str(charge.get("question_id", ""))
        if not MOTIF_NOM.match(question_id):
            self.send_response(400)
            self.end_headers()
            return
        charge["statut"] = "en_attente"
        charge["deposee_le"] = datetime.now(timezone.utc).astimezone().isoformat(
            timespec="seconds"
        )
        dossier = BASE / "questions"
        dossier.mkdir(parents=True, exist_ok=True)
        (dossier / f"{question_id}.json").write_text(
            json.dumps(charge, ensure_ascii=False, indent=1), encoding="utf-8"
        )
        self._repondre_json(201, {"ok": True})

    def _recevoir_reponse(self):
        charge = self._lire_json()
        if charge is None:
            return
        question_id = str(charge.get("question_id", ""))
        cible = BASE / "questions" / f"{question_id}.json"
        if not MOTIF_NOM.match(question_id) or not cible.is_file():
            self.send_response(404)
            self.end_headers()
            return
        try:
            question = json.loads(cible.read_text(encoding="utf-8"))
        except Exception:
            self.send_response(500)
            self.end_headers()
            return
        question["statut"] = "repondue"
        question["texte_lourd"] = str(charge.get("texte_lourd", ""))
        question["repondue_le"] = datetime.now(timezone.utc).astimezone().isoformat(
            timespec="seconds"
        )
        cible.write_text(
            json.dumps(question, ensure_ascii=False, indent=1), encoding="utf-8"
        )
        # La réponse réveille le guet : Muse intègre la lecture
        # lourde au rendez-vous temporaire de l'appel concerné.
        ecrire_run(
            {
                "cas": "relecture-reponse",
                "contexte": False,
                "modele": "large-v3-turbo (Pixel, back door)",
                "fils": 4,
                "resultat": (
                    f"RELECTURE {question_id} : "
                    f"{question['texte_lourd']}"
                ),
                "donnees": question,
            }
        )
        self._repondre_json(201, {"ok": True})

    def _recevoir_piece(self, racine, sous_dossier, fichier, taille_max):
        """Reçoit une pièce binaire brute (clip WAV d'un appel,
        page d'un scan) et la range dans <racine>/<sous_dossier>/."""
        try:
            taille = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            taille = 0
        if taille <= 0 or taille > taille_max:
            self.send_response(413)
            self.end_headers()
            return
        brut = self.rfile.read(taille)
        dossier = BASE / racine / sous_dossier
        dossier.mkdir(parents=True, exist_ok=True)
        (dossier / fichier).write_bytes(brut)
        corps = b'{"ok": true}'
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def _recevoir_audio(self, appel, fichier):
        self._recevoir_piece("appels", appel, fichier, TAILLE_MAX_AUDIO)

    def _recevoir_scan(self, scan, fichier):
        self._recevoir_piece("scans", scan, fichier, TAILLE_MAX_SCAN)

    def _recevoir_telemetrie(self, session, fichier):
        self._recevoir_piece(
            "telemetrie", session, fichier, TAILLE_MAX_TELEMETRIE
        )

    def do_GET(self):
        if route_simple(self.path, "questions"):
            dossier = BASE / "questions"
            en_attente = []
            if dossier.is_dir():
                for f in sorted(dossier.glob("*.json")):
                    try:
                        q = json.loads(f.read_text(encoding="utf-8"))
                    except Exception:
                        continue
                    if q.get("statut") == "en_attente":
                        en_attente.append(q)
            self._repondre_json(200, en_attente)
            return
        audio = morceau_audio(self.path)
        if audio is None:
            self.send_response(404)
            self.end_headers()
            return
        cible = BASE / "appels" / audio[0] / audio[1]
        if not cible.is_file():
            self.send_response(404)
            self.end_headers()
            return
        brut = cible.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(brut)))
        self.end_headers()
        self.wfile.write(brut)


if __name__ == "__main__":
    if not JETON:
        print("PONT_JETON manquant", file=sys.stderr)
        sys.exit(1)
    BASE.mkdir(parents=True, exist_ok=True)
    if MAIL_ON and MAIL_DEST:
        print(f"PONT-MAIL actif : sonnette vers {MAIL_DEST}", flush=True)
    else:
        print("PONT-MAIL inactif (PONT_MAIL_ON/PONT_MAIL_DEST)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Mains).serve_forever()
