# Pixel Bridge

Le téléphone professionnel de Grégoire Painchaud, pont local vers Muse.

Un Pixel 8 Pro qui, en plus d'être un téléphone normal, capte les appels
et les messages vocaux professionnels, les transcrit et les structure
**sur l'appareil** (Whisper + petit modèle 3-4B), entièrement hors ligne,
puis synchronise les données structurées vers Muse quand le réseau
revient.

## Contraintes fondatrices

- **Hors ligne d'abord** : capture → transcription → extraction → file,
  100 % local. La synchro est opportuniste.
- **La téléphonie ne casse jamais** : le pipeline est un passager.
- **Petit modèle** : extraction 3-4B dans 12 Go de RAM, pas de 7B+.
- **Rien ne sort avant la synchro** ; l'audio reste sur l'appareil.

Détails : [ARCHITECTURE.md](ARCHITECTURE.md).

## Structure

- `core/` — le contrat d'extraction, indépendant de l'appareil :
  schéma JSON, prompt, exemples français et harnais de validation.
  Prouvable ici : `python3 core/valider.py`
- `app/` — l'application Android (Kotlin) : guetteurs (appels, notes
  vocales, cache Messenger), pipeline, file locale, synchro.
- `.github/workflows/android.yml` — CI du modèle atelier : compile à
  chaque push, journaux sur la branche `ci-logs`.

## État (2026-11-10)

- Schéma d'extraction + prompt + harnais : **faits et prouvés**
  (3 exemples français réalistes, extraits vérifiés verbatim,
  dates relatives résolues, test négatif rejeté).
- Squelette Android : posé (service, guetteurs, pipeline, synchro) ;
  les branchements Whisper et modèle local sont des stubs explicites
  qui attendent l'appareil.
- Dépôt GitHub : **à créer** (geste de Grégoire), puis clé de
  déploiement dédiée et premier build CI.
- Appareil : Pixel 8 Pro en transit (Mobile Klinik, Purolator
  520868927473). À son arrivée : ROM/root, capture d'appels, cache
  Messenger, mesures de vitesse réelles.
