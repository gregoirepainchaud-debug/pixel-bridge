# Banc d'essai — modèles open source embarqués (Pixel 8 Pro / GrapheneOS)

But : décider, sur preuves, quels modèles open source font les trois jobs
du pont, 100 % hors ligne, sur le téléphone réel de Grégoire.

## Principes (non négociables)

1. **Corpus figé** : les mêmes entrées pour tous les modèles. On ne
   change pas un cas en cours de ronde ; un nouveau cas = nouvelle
   version du corpus.
2. **Vérité terrain écrite AVANT les tests** : transcriptions de
   référence et extractions attendues sont dans `corpus/`, relues par
   un humain. Un modèle ne peut pas « avoir raison » contre la vérité
   terrain sans qu'on modifie le corpus en le disant.
3. **Conditions constantes** : même téléphone, mode avion **activé**
   (c'est un banc d'essai HORS LIGNE), état de charge noté, **3 runs
   par cas**, on retient la médiane. Les échecs comptent et restent au
   journal.
4. **Tout est journalisé** dans `resultats.md` : modèle exact (fichier,
   quantification, source), harnais + version, date, chiffres bruts.
   Aucun résultat « de mémoire ».

## Les trois jobs et leurs métriques

### 1. Transcription (voix → texte)
- Entrées : les fichiers audio D1 et D2, enregistrés UNE fois par
  Grégoire, réutilisés pour tous les modèles.
- Métriques : taux d'erreur de mots (WER) et de caractères (CER) contre
  la transcription de référence ; temps total ; ratio temps/audio.

### 2. Extraction + mise en contexte (texte → JSON)
- Entrées : les cas du corpus (transcriptions de référence de D1, D2 +
  les 3 exemples de `core/exemples/`), le prompt `core/prompt_extraction.txt`
  et le schéma `core/extraction.schema.json`, tels quels.
- Deux variantes par modèle : **sans contexte** et **avec contexte**
  (lexique `core/lexique_qc.md` joint au prompt) — on mesure ce que le
  contexte change.
- Métriques : JSON valide ou non (via `core/valider.py`) ; exactitude
  champ par champ contre l'extraction attendue, avec les **champs
  critiques comptés à part** (montants, dates, heures, noms) ; temps
  total ; tokens/s si le harnais les donne.

### 3. Image (photo de reçu → données)
- Entrées : 10 reçus réels du corpus de Grégoire (R01 à R10, difficultés
  variées), valeurs vraies tirées de ses dossiers déjà traités.
- Métriques : champs du reçu juste ou non (marchand, date, sous-total,
  TPS, TVQ, total — le **total exact** compte double) ; temps par reçu.

### Transverse (tous les jobs)
Temps de chargement du modèle ; batterie avant/après une série ;
ralentissement entre le run 1 et le run 3 (chauffe).

## Harnais épinglés (ronde 1)

- LLM / VLM : **PocketPal AI** (open source, charge les GGUF, vision,
  mode benchmark intégré). Version notée à l'installation.
- Whisper : ronde 2 (Transcribro, modèles whisper.cpp chargés à la main).

Un seul harnais par famille par ronde : le harnais fait partie du
résultat, on le nomme dans chaque ligne du journal.

## Modèles épinglés

- **Ronde 1** : Qwen2.5-VL-3B-Instruct, GGUF **Q4_K_M** + mmproj
  (dépôt officiel Qwen sur HuggingFace : `Qwen/Qwen2.5-VL-3B-Instruct-GGUF`).
  Un seul modèle, deux jobs (extraction texte + image).
- Rondes suivantes (candidats) : Qwen3-4B et Llama 3.2 3B (texte) ;
  Whisper Small / Medium / Large-v3-Turbo ; Qwen3-VL.

## Critères de décision (proposition, à ratifier avant les chiffres)

- Extraction : JSON valide 5/5 ET ≥ 90 % des champs critiques justes
  → viable comme première passe embarquée.
- Transcription : WER ≤ 10 % sur D1+D2 → viable pour les notes terrain.
- Vision : total exact sur ≥ 8/10 reçus → première passe viable, le
  reste escalade vers Muse (chaîne ratifiée le 2026-11-10).
