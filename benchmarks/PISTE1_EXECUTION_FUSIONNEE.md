# Piste 1 — exécution fusionnée/compilée (spike de faisabilité)

Décision de Grégoire, 2026-11-16 au soir : pour viser ~11 t/s en
génération, on commence par la piste 1 du rapport « Synchronisation
des cœurs » — remplacer l'exécution opérateur-par-opérateur de ggml
(~600 barrières de synchronisation par jeton) par une exécution
compilée et fusionnée.

## Candidat retenu : ExecuTorch + XNNPACK

- ExecuTorch (PyTorch) exporte le modèle en un programme `.pte`
  compilé à l'avance ; le backend XNNPACK exécute des opérateurs
  fusionnés avec ses propres micro-noyaux ARM. C'est la pile des
  mesures citées au rapport (46 ms contre 106 ms par jeton sur un
  modèle 1,2B / Tensor, face à llama.cpp).
- Support vérifié le 2026-11-16 (documentation officielle
  docs.pytorch.org, « Exporting LLMs ») : **Qwen 2.5 fait partie des
  modèles supportés** par l'API `export_llm` (avec Llama 2/3,
  Qwen 3, Phi, SmolLM2). La liste MediaTek nomme même
  explicitement « Qwen2.5-3B ».
- Runtime Android publié sur Maven :
  `org.pytorch:executorch-android` (1.4.0 ; la documentation montre
  une version plus ancienne — vérifier sur Maven au moment
  d'intégrer), API `LlmModule` pour le chargement et la génération
  en flux. S'intègre comme dépendance Gradle dans l'app existante.

## Périmètre du spike : le TEXTE seulement

- Modèle : **Qwen2.5-3B-Instruct** (le frère texte de notre
  Qwen2.5-VL-3B — même famille, même taille). L'extraction de notes
  est un travail texte ; c'est elle qu'on mesure.
- **Qwen2.5-VL n'a pas de chemin d'exportation pavé** dans
  ExecuTorch (les VLM supportés sont d'autres modèles). La vision
  (reçus, mmproj) reste sur le harnais llama.cpp actuel, inchangée.
- Quantification : la voie ExecuTorch utilise ses propres formats
  (8da4w — activations dynamiques 8 bits, poids 4 bits — ou int4
  par groupes), **pas** le Q4_K_M du GGUF. Le `.pte` est donc un
  artefact numérique différent : la qualité se re-mesure, elle ne
  s'hérite pas.

## Étapes

1. **Exportation sur le Mac de Grégoire** (patron atelier) :
   installer l'environnement ExecuTorch, télécharger
   Qwen2.5-3B-Instruct depuis HuggingFace, exporter en `.pte`
   XNNPACK quantifié 4 bits. La VM de Muse (6 Go partagés) ne
   tient pas cette exportation ; le Mac, oui.
2. **Module de banc dans l'app** : un écran jumeau de l'actuel,
   même protocole — cas D1 figé, mode avion, 3 runs, mêmes
   métriques (chargement, lecture, génération, validation
   mécanique de la sortie contre la vérité terrain).
3. **Comparaison à armes égales** contre la configuration
   championne ggml du 2026-11-16 : génération 2 fils épinglés /
   lecture 5 fils épinglés, poll 100 → 6,4 t/s à froid,
   5 t/s à chaud ; qualité de référence = runs 6/11.

## Critère de décision (à ratifier avant les chiffres)

- Génération ≥ 9 t/s sur D1 **et** qualité au moins égale aux
  runs 6/11 (champs critiques justes, écarts de schéma du même
  ordre) → ExecuTorch devient le moteur texte ; ggml garde la
  vision.
- Sinon : le spike est documenté au journal avec ses chiffres,
  on reste sur ggml optimisé, et la piste 2 (décodage
  spéculatif) devient la candidate suivante.

## Risques connus

- Format numérique différent (8da4w contre Q4_K_M) : la loterie
  des arrondis repart à zéro — 3 runs obligatoires avant tout
  verdict, comme toujours.
- Gabarit de conversation et tokenizer à brancher via
  `LlmModule` ; vérifier que la sortie en flux et l'arrêt propre
  se comportent comme dans le harnais actuel.
- Versions : épingler la version ExecuTorch du runtime Android
  ET de l'outil d'exportation ensemble (les deux doivent parler
  le même format `.pte`).
