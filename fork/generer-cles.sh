#!/usr/bin/env bash
# =====================================================================
# generer-cles.sh — Génération des clés de signature du fork
# GrapheneOS (une variante d'appareil : husky, le Pixel 8 Pro).
#
# ⚠️  NON TESTÉ — 2026-11-17. Aucune machine de build n'existe
# encore ; ce script n'a jamais été exécuté. Il suit pas à pas la
# procédure officielle de https://grapheneos.org/build (section
# « Keys »), mais il devra être relu et éprouvé le jour J, sur la
# machine de build, sous supervision. Ne pas lancer ailleurs.
#
# À EXÉCUTER depuis la racine d'un arbre de sources GrapheneOS
# déjà synchronisé (repo sync terminé).
#
# RÈGLES D'OR (documentation officielle) :
# - Des clés UNIQUES par variante d'appareil (ici : husky).
# - La phrase de passe doit être IDENTIQUE pour toutes les clés
#   (compatibilité avec script/encrypt-keys et script/decrypt-keys).
#   Elle est choisie par Grégoire et saisie par lui — jamais écrite
#   dans un fichier, jamais transmise à la machine autrement qu'au
#   clavier lors de la génération.
# - Changer de clés plus tard = reflasher les images d'usine =
#   réinitialisation complète du téléphone. On génère UNE fois.
# - Dès la génération terminée : SAUVEGARDER le dossier keys/
#   chez Grégoire (support chiffré), jamais uniquement sur la
#   machine de build louée.
# =====================================================================

set -euo pipefail

APPAREIL="${1:-husky}"
# Sujet des certificats — l'exemple officiel est CN=GrapheneOS ;
# la documentation demande de le remplacer par nos informations.
CN="${CN:-Pixel Bridge}"

# --- Garde-fous -------------------------------------------------------
# Refuser de rouler hors d'un arbre de sources GrapheneOS.
if [[ ! -x development/tools/make_key || ! -f external/avb/avbtool.py ]]; then
  echo "ERREUR : development/tools/make_key ou external/avb/avbtool.py" >&2
  echo "introuvable. Lancer ce script depuis la racine d'un arbre" >&2
  echo "de sources GrapheneOS synchronisé." >&2
  exit 1
fi

# Refuser d'écraser des clés existantes : régénérer = casser le
# chemin de mise à jour de tout appareil déjà flashé.
if [[ -e "keys/$APPAREIL" ]]; then
  echo "ERREUR : keys/$APPAREIL existe déjà. On ne régénère jamais" >&2
  echo "des clés par-dessus des clés existantes." >&2
  exit 1
fi

mkdir -p "keys/$APPAREIL"
chmod 700 "keys/$APPAREIL"
cd "keys/$APPAREIL"

# --- Les neuf clés de signature d'applications -----------------------
# Liste exacte de la documentation officielle (make_key demande la
# phrase de passe à chaque clé : toujours la même).
for nom in releasekey platform shared media networkstack bluetooth \
           sdk_sandbox gmscompat_lib nfc; do
  "../../development/tools/make_key" "$nom" "/CN=$CN/"
done

# --- La clé AVB (Verified Boot) ---------------------------------------
# Clé privée avb.pem (chiffrée scrypt via pkcs8 -topk8) ; la clé
# publique avb_pkmd.bin est celle que le téléphone utilisera pour
# faire respecter le démarrage vérifié avec nos signatures (elle
# est enrôlée au reverrouillage du bootloader).
openssl genrsa 4096 | openssl pkcs8 -topk8 -scrypt -out avb.pem
../../external/avb/avbtool.py extract_public_key \
  --key avb.pem --output avb_pkmd.bin

cd ../..

# --- La clé OpenSSH de signature des images d'usine -------------------
# Même phrase de passe que les autres clés (la doc le demande ;
# les scripts ne la gèrent pas encore automatiquement).
ssh-keygen -t ed25519 -f "keys/$APPAREIL/id_ed25519"

# --- ÉTAPE OPTIONNELLE : clé Vanadium ---------------------------------
# Nécessaire SEULEMENT si on reconstruit Vanadium (le navigateur /
# WebView) depuis ses sources plutôt que d'utiliser le précompilé
# de l'arbre officiel. Question ouverte pour le jour J — laisser
# commenté d'ici là.
#
# keytool -genkey -v -keystore vanadium.keystore -storetype pkcs12 \
#   -alias vanadium -keyalg RSA -keysize 4096 -sigalg SHA512withRSA \
#   -validity 10000 -dname "cn=$CN"

# --- Chiffrement au repos ----------------------------------------------
# La documentation fournit script/encrypt-keys pour (re)chiffrer
# l'ensemble des clés d'un appareil avec la phrase de passe unique.
# À lancer une fois la génération vérifiée :
echo
echo "Clés générées dans keys/$APPAREIL :"
ls -1 "keys/$APPAREIL"
echo
echo "Prochaines étapes MANUELLES, dans l'ordre :"
echo "  1. script/encrypt-keys keys/$APPAREIL"
echo "  2. SAUVEGARDE immédiate de keys/ chez Grégoire (support"
echo "     chiffré, hors de la machine de build)."
echo "  3. Ne jamais régénérer : voir les règles d'or en en-tête."
