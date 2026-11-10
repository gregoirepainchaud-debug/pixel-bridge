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
