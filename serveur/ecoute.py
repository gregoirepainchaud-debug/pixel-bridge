#!/usr/bin/env python3
"""Oreille du pont Pixel — reçoit les résultats du banc d'essai.

Service minimal (bibliothèque standard seulement) : l'app du Pixel
envoie un JSON par run à POST /pont/<jeton>/run ; chaque résultat
est copié dans runs/<horodatage>_<cas>.json et appendé à
runs.jsonl ; etat.json tient le compteur que le guet de Muse
consulte pour savoir qu'un nouveau résultat est arrivé.
Aucune autre route, aucune écriture ailleurs.
"""
import json
import os
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

JETON = os.environ.get("PONT_JETON", "")
BASE = Path(os.environ.get("PONT_DIR", "/var/lib/pont"))
PORT = int(os.environ.get("PONT_PORT", "8100"))
TAILLE_MAX = 512 * 1024


class Mains(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if not JETON or self.path != f"/pont/{JETON}/run":
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

    def do_GET(self):
        self.send_response(404)
        self.end_headers()


if __name__ == "__main__":
    if not JETON:
        print("PONT_JETON manquant", file=sys.stderr)
        sys.exit(1)
    BASE.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Mains).serve_forever()
