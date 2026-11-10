# Pixel Bridge — architecture de travail

Document court, vivant. Décision de Grégoire (2026-11-10) : on construit
AVANT le cahier des charges complet ; ce fichier consigne les choix au fil
de l'eau, pas une spec figée.

## But

Le Pixel 8 Pro devient le **cellulaire professionnel** de Grégoire d'un
bout à l'autre. En plus de la téléphonie normale, il capte les éléments
professionnels qui passent par le téléphone (appels, messages vocaux),
les transcrit et les structure **sur l'appareil**, puis synchronise vers
Muse quand le réseau le permet.

## Contraintes fondatrices

1. **HORS LIGNE d'abord.** Grégoire se retrouve souvent sans couverture.
   Capture → transcription → extraction → file : 100 % local, aucune
   fonction ne dépend d'une connexion. La synchro est opportuniste.
2. **La téléphonie ne casse jamais.** Le pipeline est un passager discret :
   s'il plante, le téléphone continue de téléphoner. Batterie protégée :
   les gros traitements partent quand l'appareil est branché ou en pause.
3. **Petit modèle local.** Extraction = modèle 3-4B (12 Go de RAM).
   Pas de 7B+ (1-3 jetons/s = inutilisable). Transcription = Whisper
   local (whisper.cpp), français.
4. **Rien ne sort avant la synchro.** L'audio reste sur l'appareil ; la
   synchro pousse d'abord les données structurées (légères), l'audio ne
   suit que sur demande (réalité des connexions intermittentes).

## Pipeline

```
SOURCES                INGESTION          TRAITEMENT            SORTIE
─────────────────────  ────────────────   ───────────────────   ──────────────
Appels téléphoniques   Guetteurs  ──►     File locale (SQLite)  File de sortie
Notes vocales WhatsApp (fichiers    ──►   Transcription         (JSON validé)
  et autres apps)        audio +          (Whisper)        ──►  Synchro
Messages vocaux          métadonnées)     Extraction            opportuniste
  Messenger (cache                        (modèle 3-4B,            │
  via root ; repli :                      schéma fixe)             ▼
  capture à la lecture)              Validation du JSON      Muse (à définir)
```

Chaque élément porte un état : `nouveau → transcrit → extrait → validé →
en file de synchro → synchronisé`. Un échec à une étape ne perd jamais
l'élément : il reste dans la file avec son erreur.

## Schéma d'extraction

Le contrat est `core/extraction.schema.json` : contact, résumé,
rendez-vous (dates résolues en ISO grâce aux métadonnées de l'élément —
jour et heure de réception fournis au modèle), adresses, numéros
mentionnés, montants, engagements. Chaque donnée extraite porte son
**extrait verbatim** et un **niveau de confiance** — Grégoire valide d'un
coup d'œil au lieu de réécouter. Ce que le modèle ne peut pas résoudre
va dans `incertitudes`, jamais inventé.

Le harnais `core/valider.py` prouve le schéma sur des exemples français
réalistes (`core/exemples/`) sans l'appareil.

## À valider sur l'appareil réel (rien de ceci n'est tenu pour acquis)

- Capture d'appels sous ROM custom/root : qualité des deux voix, fiabilité.
- Vitesse réelle : Whisper et le modèle 3-4B sur Tensor G3.
- Cache Messenger : chemin, format, stabilité entre mises à jour.
- « Hold for Me » de l'app Téléphone stock : disponibilité sous ROM
  custom NON vérifiée — aucune hypothèse bâtie dessus.
- Consommation batterie d'une journée complète de pipeline.

## Exigence : Google Play Store (2026-11-10)

Le téléphone doit se connecter au Play Store comme un cell pro normal.
Options selon la base du fork : Play Store officiel sandboxé
(GrapheneOS), ou Play Store complet via GApps (base type LineageOS).
Réserve : Play Integrity peut bloquer Google Pay et certaines apps
bancaires sur appareil modifié — à tester avec les apps de Grégoire.
Piste privilégiée pour notre fork : le pont intégré comme composant
système signé par nos propres clés, plutôt que dépendant du root.

## Vision : Gestion dans l'OS, kilométrage en coulisses (2026-11-10)

- **Gestion préinstallée dans le fork** : citoyenne de première classe,
  alimentée directement par le pont (extractions → rendez-vous, suivis),
  avec une couche locale hors ligne qui synchronise au retour du réseau
  (Gestion est aujourd'hui une app web sur le VPS — le local-first est
  un chantier assumé, aligné sur la contrainte hors ligne).
- **Kilométrage 100 % coulisses** : détection des déplacements et
  journalisation en arrière-plan, sans aucun geste de Grégoire.
  Suite de l'ère GPS de Gestion (suivi + recollage) ; les lectures
  d'odomètre ne servent que d'ancres de calibration.

## Échelle de délégation à l'IA embarquée (2026-11-10)

Principe : l'IA locale prend les réflexes (immédiat, hors ligne) ;
Muse en ligne garde le jugement (exceptions, ambiguïtés, audits).
Ce qui est déterministe est du CODE, pas un modèle (mécanisation).

- **Facture du client en cours** — facile : remplissage structuré
  depuis les données déjà extraites ; taxes, numérotation et totaux
  calculés en code ; Grégoire valide avant envoi (règle inchangée).
- **Extraction appels / messages vocaux** — le cœur du projet,
  contrat déjà prouvé (voir core/).
- **Interprétation des reçus** — le morceau dur (vision + jugement) :
  première passe embarquée vers le même schéma structuré, avec
  confiances ; contrôles mécaniques en code (réconciliation des taxes,
  détection de doublons — comme les protocoles serveur actuels) ;
  les cas sous le seuil de confiance sont signalés ou escaladés.
  Banc d'essai réel : le corpus de reçus déjà traités (avant/après).

## Optimisation québécoise (2026-11-10)

Le français de Grégoire est québécois, anglicismes inclus — le système
comprend, il ne « corrige » pas. Couche `core/lexique_qc.md` (vivante) :
- transcription : le lexique sert de prompt de vocabulaire à Whisper ;
- extraction : le lexique est joint au prompt du modèle local.
Conventions codées : dates parlées québécoises, montants « 1 540,95 $ »,
heures « et demie / et quart / moins quart », téléphone par groupes.
