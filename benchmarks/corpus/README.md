# Corpus du banc d'essai — version 1 (figée le 2026-11-16)

Toute modification d'un cas = nouvelle version du corpus, dite au journal.

## Transcription + extraction (audio)
- **D1** — `cas_d1_note_accord.json` : note vocale d'après-accord.
  Audio : `d1_note_accord.m4a` (à enregistrer par Grégoire, une fois).
- **D2** — `cas_d2_vocal_gig.json` : message vocal d'une cliente, cas dur
  (anglicismes, date ambiguë, trois heures, numéro dicté).
  Audio : `d2_vocal_gig.m4a` (à enregistrer par Grégoire, une fois).

## Extraction (texte seul)
- D1 et D2 (leurs transcriptions de référence), plus les trois exemples
  du contrat, réutilisés tels quels :
  `core/exemples/ex1_appel_accord.json`, `ex2_vocal_messenger.json`,
  `ex3_note_whatsapp.json`. Total : 5 cas.

## Image (reçus) — ASSEMBLÉ (2026-11-16, depuis l'app Gestion)
- **R01 à R10** dans `recus/` : pour chacun, l'image (`rNN.jpg`, copie
  du scan stocké dans Gestion, /var/lib/gestion/recus/) + la fiche
  vérité terrain (`rNN.json`) : marchand, date, sous-total, TPS, TVQ,
  total, devise, note de difficulté, et la source (nº du reçu Gestion +
  id de la dépense). Tous les reçus sont au statut « revise » et leurs
  montants ont été croisés avec la dépense liée (écart 0 partout).
- Choix (difficultés variées, constatées sur les images) :
  - R01 Bureau en Gros (62,11 $) — thermique, transparence du verso,
    lignes de rabais négatives.
  - R02 Canadian Tire (1 267,83 $) — facture d'atelier multiligne,
    TPS/TVQ, relevé Interac joint.
  - R03 Metro (30,47 $) — épicerie multiligne, bord gauche rogné ;
    vraie date 2025-08-23 (le champ date Gestion = plancher T3).
  - R04 Hudson Group (17,42 $) — ANGLAIS (aéroport de Halifax), photo
    pivotée à 90°, froissé, TVH 14 % au lieu de TPS/TVQ ; vérité
    terrain = traitement « Resto - ROC » du dossier (voir la fiche).
  - R05 Garage Patrick Garneau (195,40 $) — thermique très pâle et
    flou, le plus dur ; prix prouvé par les taxes ; vraie date
    2025-11-26 (plancher T3 dans Gestion).
  - R06 Clinique d'acupuncture Xuemei Song (95,00 $) — formulaire
    imprimé rempli À LA MAIN, aucune taxe.
  - R07 Société des traversiers du Québec (10,20 $) — thermique propre,
    TPS/TVQ imprimées à 0,00 $.
  - R08 Sylvain Neault (402,41 $) — facture numérique nette (le cas
    facile) ; piège : sa case « Sous-Total » contient le total.
  - R09 Entrepôt de l'électro (2 276,47 $) — imprimé pâle et froissé ;
    taxes TPS+TVQ fusionnées sur le document, ventilées au dossier.
  - R10 McDonald's (21,67 $) — thermique resto net ; bannière déduite
    de l'exploitant (Gestion N. Auger inc., Montmagny).
- Convention « date » des fiches : la vraie date DU DOCUMENT (celle
  qu'un lecteur parfait doit extraire). Quand le champ date de Gestion
  porte une date plancher T3 différente, la fiche le dit.
