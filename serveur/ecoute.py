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
- GET /pont/<jeton>/audio/<appel>/<fichier>.wav : sert le clip,
  pour que Grégoire puisse réécouter le bout pertinent quand ni
  Whisper ni Muse n'ont été concluants.
Aucune autre route, aucune écriture ailleurs.
"""
import json
import os
import re
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

JETON = os.environ.get("PONT_JETON", "")
BASE = Path(os.environ.get("PONT_DIR", "/var/lib/pont"))
PORT = int(os.environ.get("PONT_PORT", "8100"))
TAILLE_MAX = 512 * 1024
TAILLE_MAX_AUDIO = 12 * 1024 * 1024
MOTIF_NOM = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


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


class Mains(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        audio = morceau_audio(self.path)
        if audio is not None:
            self._recevoir_audio(*audio)
            return
        if not JETON or self.path.split("?")[0] != f"/pont/{JETON}/run":
            self.send_response(404)
            self.end_headers()
            return
        try:
            taille = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            taille = 0
        if taille <= 0 or taille > TAILLE_MAX:
            self.send_response(413)
            self.end_headers()
            return
        brut = self.rfile.read(taille)
        try:
            charge = json.loads(brut.decode("utf-8"))
        except Exception:
            self.send_response(400)
            self.end_headers()
            return
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
        corps = b'{"ok": true}'
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def _recevoir_audio(self, appel, fichier):
        try:
            taille = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            taille = 0
        if taille <= 0 or taille > TAILLE_MAX_AUDIO:
            self.send_response(413)
            self.end_headers()
            return
        brut = self.rfile.read(taille)
        dossier = BASE / "appels" / appel
        dossier.mkdir(parents=True, exist_ok=True)
        (dossier / fichier).write_bytes(brut)
        corps = b'{"ok": true}'
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def do_GET(self):
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
    ThreadingHTTPServer(("127.0.0.1", PORT), Mains).serve_forever()
