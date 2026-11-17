# Fork GrapheneOS — le jour J, étape par étape

L'ordre exact des opérations le jour où la machine de build existe.
Le plan complet et les raisons sont dans `docs/FORK_GRAPHENEOS.md` ;
ce fichier-ci est la liste d'exécution. Les étapes marquées
**🛑 ARRÊT** exigent Grégoire en personne — tout le reste, Muse le
pilote en SSH comme pour ses autres chantiers.

## Phase 0 — Comptes et dépôts

1. **🛑 ARRÊT — session GitHub de Grégoire.** Dans son navigateur,
   connecté à son compte :
   - forker `GrapheneOS/platform_manifest` dans son compte ;
   - créer le dépôt d'overlay de nos applications (nom de travail :
     `pixelbridge-external`) ;
   - (Muse n'a pas accès au compte GitHub de Grégoire : cette
     étape ne peut pas être déléguée. La clé de déploiement du
     dépôt pixel-bridge ne couvre que ce dépôt-ci.)
2. Muse prépare le `local_manifests` (déclaration de l'overlay,
   aucun remplacement amont) et le commit dans le manifest forké.

## Phase 1 — La machine

3. **🛑 ARRÊT — la machine elle-même.** Au choix de Grégoire
   (options et prix vérifiés dans le plan, section 7) :
   - commande du dédié OVH (son compte, son paiement ; Beauharnois
     si disponible), **ou**
   - préparation de son vieux PC (specs à valider : RAM ≥ 32 Go,
     ≥ ~400 Go libres sur SSD).
   Installation de **Debian 12 (bookworm)**.
4. Accès SSH pour Muse sur la machine (clé dédiée, comme le VPS).
5. Installation des dépendances de build (liste du plan, section 2.1).

## Phase 2 — Sources et clés

6. `repo init` avec **le manifest forké de Grégoire** sur le tag
   stable visé ; vérification de la signature du tag amont
   (`allowed_signers` + `git verify-tag`) ; `repo sync -j8`
   (~136 Gio — laisser rouler).
7. **🛑 ARRÊT — génération des clés.** `fork/generer-cles.sh husky`
   (script NON TESTÉ : le relire avant de lancer). La **phrase de
   passe est choisie et saisie par Grégoire** ; elle est unique
   pour toutes les clés. Puis `script/encrypt-keys keys/husky`.
8. **🛑 ARRÊT — sauvegarde des clés.** Copie du dossier `keys/`
   chez Grégoire, sur support chiffré, **avant** tout build. La
   machine louée ne doit jamais être le seul endroit où vivent
   les clés.

## Phase 3 — Premier build

9. `source build/envsetup.sh` ; `lunch husky-cur-user`.
10. Build de production, propre :
    `rm -r out` puis
    `m vendorbootimage vendorkernelbootimage target-files-package`,
    puis `m otatools-package`.
    (Quelques heures ; Muse surveille et rapporte.)
11. Signature et release : `script/finalize.sh` puis
    `script/generate-release.sh husky <BUILD_NUMBER>`.
    Vérifier la présence des images d'usine et du paquet OTA dans
    `releases/<BUILD_NUMBER>/release-husky-<BUILD_NUMBER>/`.

## Phase 4 — Premier flash

12. **🛑 ARRÊT — le Pixel dans les mains de Grégoire**, branché en
    USB, avec lui devant l'écran pour toute la séquence :
    - réactiver « Déverrouillage OEM » et les options développeur ;
    - déverrouiller le bootloader (**efface l'appareil**) ;
    - flasher les images d'usine de notre build ;
    - reverrouiller avec notre clé AVB ;
    - refermer : Déverrouillage OEM éteint, options développeur
      fermées (la discipline en vigueur depuis le 2026-11-16).
13. **Batterie d'épreuve, dans cet ordre** (les mêmes tests que le
    2026-11-16 sur l'officiel) :
    1. **Desjardins** — le test Play Integrity qui compte ;
    2. Android Auto sans fil (la smartbox de l'auto) ;
    3. appels et Play Store sandboxé ;
    4. nos applications : l'assistant en priv-app (permissions de
       `privapp-permissions-pixelbridge.xml` effectivement
       détenues), Gestion préinstallée.
14. **Décision de Grégoire** : le fork devient le système du
    téléphone, ou retour à l'officiel (installateur web officiel —
    le chemin de retour est documenté au plan, section 10).

## Phase 5 — Régime de croisière (après l'épreuve seulement)

15. Serveur OTA maison : sous-domaine dédié, hébergement statique
    des releases ; **changer l'URL de l'Updater**
    (`packages/apps/Updater/res/values/config.xml`) vers notre
    serveur avant tout build `OFFICIAL_BUILD=true` — obligation
    absolue de la documentation officielle.
16. Cadence mensuelle : nouveau tag → resync → build → signature →
    canal `testing` sur l'appareil → `stable`. Conserver les
    target-files signés de chaque release (deltas).
