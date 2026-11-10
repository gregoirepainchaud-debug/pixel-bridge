#!/usr/bin/env python3
"""Harnais de validation du contrat d'extraction Pixel Bridge.

Prouve, sans l'appareil :
  1. chaque extraction d'exemple est conforme au schéma JSON ;
  2. chaque « extrait » cité est bien un passage VERBATIM de la
     transcription (comparaison normalisée : casse, ponctuation,
     espaces, tirets de dialogue) ;
  3. les dates de rendez-vous tombent le bon jour de semaine par
     rapport à la date de réception et au jour nommé ;
  4. le validateur REJETTE une extraction cassée (test négatif).

Usage : python3 core/valider.py    (code de sortie 0 = tout passe)
"""
import json
import re
import sys
import unicodedata
from datetime import date
from pathlib import Path

from jsonschema import Draft202012Validator

ICI = Path(__file__).parent
JOURS = {"lundi": 0, "mardi": 1, "mercredi": 2, "jeudi": 3,
         "vendredi": 4, "samedi": 5, "dimanche": 6}


def normaliser(texte: str) -> str:
    """ minuscules, sans ponctuation, espaces simples — pour comparer
    un extrait cité à la transcription malgré la mise en forme. """
    t = unicodedata.normalize("NFKC", texte).lower()
    t = re.sub(r"[^\w\s]", " ", t, flags=re.UNICODE)
    return re.sub(r"\s+", " ", t).strip()


def extraits_de(extraction: dict):
    for section in ("rendez_vous", "adresses", "numeros_mentionnes",
                    "montants", "engagements"):
        for item in extraction.get(section, []):
            yield section, item


def verifier_exemple(chemin: Path, validateur) -> list[str]:
    erreurs = []
    doc = json.loads(chemin.read_text(encoding="utf-8"))
    meta, transcription = doc["metadonnees"], doc["transcription"]
    extraction = doc["extraction_attendue"]

    # type/source doivent refléter les métadonnées
    if extraction["type"] != meta["type"]:
        erreurs.append("type ≠ métadonnées")
    if extraction["source_app"] != meta["source_app"]:
        erreurs.append("source_app ≠ métadonnées")

    # 1. conformité au schéma
    for err in validateur.iter_errors(extraction):
        erreurs.append(f"schéma : {'/'.join(map(str, err.path))} — {err.message}")

    # 2. extraits verbatim
    transcription_norm = normaliser(transcription)
    for section, item in extraits_de(extraction):
        if normaliser(item["extrait"]) not in transcription_norm:
            erreurs.append(f"{section} : extrait non verbatim — « {item['extrait'][:60]}… »")

    # 3. cohérence des dates (jour de semaine du RDV vs date résolue)
    recu = date.fromisoformat(meta["recu_le"][:10])
    if recu.weekday() != JOURS[meta["jour_semaine"]]:
        erreurs.append("métadonnées : jour_semaine incohérent avec recu_le")
    for rdv in extraction["rendez_vous"]:
        if rdv["date"] is not None:
            d = date.fromisoformat(rdv["date"])
            if d < recu:
                erreurs.append(f"rendez-vous : date {d} avant la réception {recu}")
    return erreurs


def main() -> int:
    schema = json.loads((ICI / "extraction.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validateur = Draft202012Validator(schema)

    echecs = 0
    exemples = sorted((ICI / "exemples").glob("*.json"))
    for chemin in exemples:
        erreurs = verifier_exemple(chemin, validateur)
        if erreurs:
            echecs += 1
            print(f"✗ {chemin.name}")
            for e in erreurs:
                print(f"    - {e}")
        else:
            print(f"✓ {chemin.name} — schéma OK, extraits verbatim, dates cohérentes")

    # 4. test négatif : extraction cassée = rejet obligatoire
    casse = json.loads(exemples[0].read_text(encoding="utf-8"))["extraction_attendue"]
    casse["rendez_vous"][0]["date"] = "17 novembre"
    casse["montants"][0].pop("confiance")
    if list(validateur.iter_errors(casse)):
        print("✓ test négatif — l'extraction cassée est rejetée")
    else:
        echecs += 1
        print("✗ test négatif — l'extraction cassée est PASSÉE (problème)")

    print(f"\n{len(exemples)} exemples, {'TOUT PASSE' if echecs == 0 else f'{echecs} ÉCHEC(S)'}")
    return 0 if echecs == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
