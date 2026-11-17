# Fork GrapheneOS — plan du palier 3

Document de préparation, rédigé le 2026-11-17. Aucune machine de build
n'existe encore, aucun dépôt amont n'a été forké : ce fichier décrit le
chantier au complet pour que le jour J soit une exécution, pas une
exploration. Sources principales : la documentation officielle de build
de GrapheneOS (https://grapheneos.org/build, lue le 2026-11-17) et les
pages tarifaires d'OVHcloud Canada (vérifiées le 2026-11-17).

## 1. Où ce chantier s'insère

Méthode ratifiée le 2026-11-15 : (A) GrapheneOS officiel = terrain de
jeu ; (B) le pont et Gestion vivent comme applications ordinaires ;
(C) les murs du palier 2 définissent les privilèges du fork ; (D) le
fork = **emballage final** de fonctions déjà prouvées. On ne débogue
jamais une fonction dans un build d'OS.

Le fork, c'est donc : les sources officielles de GrapheneOS, signées
avec **nos propres clés**, avec nos applications (le pont/assistant et
Gestion) installées comme applications système privilégiées
(priv-app). Le téléphone reste un GrapheneOS — sécurité, GmsCompat
(Play sandboxé, Android Auto), durcissement — mais l'assistant devient
un citoyen du système au lieu d'un locataire.

Grégoire a nommé la chose sans détour : « c'est un spyware notre
fork ». Domestiqué : sur SON téléphone, pour SON assistant, sous SON
contrôle. La section 5 détaille les capacités visées, leurs gâchettes
de contexte et l'exclusion dure des mots de passe.

Appareil cible : Pixel 8 Pro, nom de code **`husky`**.

## 2. Procédure officielle de build (grapheneos.org/build)

### 2.1 Prérequis machine (chiffres de la documentation officielle)

- **Système** : Linux x86_64. OS officiellement supportés pour le
  build : Arch Linux, Debian 12 (bookworm), Ubuntu 24.04 LTS,
  Ubuntu 24.10. Ni macOS ni Windows ne sont des options (le support
  macOS a été abandonné par Google en amont).
- **Mémoire** : **32 Gio ou plus**. C'est le plancher officiel ; les
  étapes de liaison d'un build complet sont le facteur critique —
  64 Gio = confortable et visé.
- **Disque** : **136 Gio et plus** pour une synchronisation standard
  des sources avec historique (90 Gio et plus en synchro légère),
  **plus 100 Gio et plus** d'espace libre supplémentaire pour un
  build typique de l'OS complet. En pratique, on vise 400-500 Go
  libres pour garder l'arbre, le répertoire `out/`, les releases
  signées et les target-files des deltas.
- **Paquets (Debian)** : `repo yarnpkg zip rsync`, plus les
  dépendances AOSP non fournies par l'arbre (diffutils, freetype2,
  fontconfig et une police, inetutils pour `hostname`, openssl,
  unzip) ; Node.js 24 LTS + yarn pour adevtool (extraction des
  fichiers vendor) ; git-lfs, gperf, glibc/gcc 32 bits pour
  Vanadium ; openssh (ssh-keygen) pour signer les images d'usine.
  L'essentiel de la chaîne d'outils est fourni précompilé par
  l'arbre lui-même (builds reproductibles).

### 2.2 Étapes officielles

1. **Choisir un tag stable**, pas la branche de développement : la
   documentation recommande le tag de release le plus récent pour
   l'appareil (la branche courante de développement est `17`,
   Android 17). L'historique stable de GrapheneOS passe par les
   tags ; on se cale sur le même tag que le build officiel installé
   sur le Pixel, ou son successeur.
2. **Initialiser le dépôt** :
   `repo init -u https://github.com/GrapheneOS/platform_manifest.git -b refs/tags/<TAG>`
   (avec notre manifest forké le jour J — voir section 3).
3. **Vérifier le manifest** : GrapheneOS signe ses tags (SSH) —
   récupérer `https://grapheneos.org/allowed_signers`, configurer
   `gpg.ssh.allowedSignersFile`, `git verify-tag` dans `.repo/manifests`.
4. **Synchroniser** : `repo sync -j8`. ~136 Gio à télécharger ;
   reprenable en cas de coupure.
5. **Générer nos clés** (section 2.3) — une seule fois, pour toujours.
6. **Préparer l'environnement** : `source build/envsetup.sh`
   (bash ou zsh obligatoire), puis la cible :
   `lunch husky-cur-user`. Le `user` (pas `userdebug`) est le build
   de production : plus sûr, c'est celui qu'on signe.
7. **Compiler** : pour le Pixel 8 Pro, la documentation impose la
   forme explicite :
   `m vendorbootimage vendorkernelbootimage target-files-package`.
   Pour un build de production, repartir propre : `rm -r out` avant.
8. **Outillage OTA** : `m otatools-package`.
9. **Signer et produire la release** : les scripts fournis par
   GrapheneOS font le travail —
   `script/finalize.sh` puis
   `script/generate-release.sh husky <BUILD_NUMBER>`.
   Résultat dans `releases/<BUILD_NUMBER>/release-husky-<BUILD_NUMBER>/` :
   images d'usine (factory) + paquet de mise à jour OTA (zip complet).
10. **Flasher** les images d'usine (section 9) ou servir l'OTA
    (section 8).

### 2.3 Les clés (une fois, pour toujours)

Procédure officielle, par variante d'appareil — des clés **uniques
par appareil**, donc un jeu pour `husky`, rangé dans `keys/husky/` :

- Neuf clés de signature d'APK/certificats via `development/tools/make_key` :
  `releasekey`, `platform`, `shared`, `media`, `networkstack`,
  `bluetooth`, `sdk_sandbox`, `gmscompat_lib`, `nfc`. Le sujet du
  certificat (l'exemple officiel est `/CN=GrapheneOS/`) doit être
  remplacé par le nôtre.
- La clé AVB (Verified Boot) : `openssl genrsa 4096 | openssl pkcs8
  -topk8 -scrypt -out avb.pem`, puis la clé publique
  `avb_pkmd.bin` extraite avec `avbtool.py` — c'est elle que le
  téléphone utilisera pour faire respecter le démarrage vérifié
  avec nos signatures.
- Une clé OpenSSH ed25519 (`keys/husky/id_ed25519`) pour signer les
  images d'usine.
- **La phrase de passe doit être identique pour toutes les clés**
  d'un appareil (compatibilité avec les scripts `encrypt-keys` /
  `decrypt-keys` de GrapheneOS, qui chiffrent les clés au repos via
  scrypt + AES-256).
- Conséquence structurante, écrite noir sur blanc dans la doc :
  **changer de clés = reflasher les images d'usine = réinitialisation
  complète du téléphone.** Les clés se génèrent une fois, se
  sauvegardent chez Grégoire (jamais uniquement sur la machine
  louée), et ne se régénèrent jamais à la légère.
- Le script `fork/generer-cles.sh` de ce dépôt automatise cette
  génération (marqué NON TESTÉ — voir section 10).

### 2.4 Temps attendus (estimations honnêtes)

La documentation officielle ne donne pas de durées ; ce qui suit est
une estimation réaliste pour les machines de la section 7, à
confirmer au premier build :

- `repo sync` initial : ~136 Gio — de l'ordre de 45 min à 2 h selon
  la bande passante réelle et le parallélisme.
- Premier build complet (`husky`, production, `out/` vide) :
  **~1,5 à 3 h** sur 12 cœurs/24 fils, **~2 à 4 h** sur 8 cœurs/16 fils.
- Signature + génération de la release : dizaines de minutes.
- Rebuilds suivants pour une release mensuelle : on repart propre
  (`rm -r out`) pour les builds de production, donc même ordre de
  grandeur à chaque fois. Les builds incrémentaux rapides (10-40 min)
  servent au développement, pas aux releases signées.

## 3. Surface minimale de fork

Principe : **toucher le moins possible au code amont.** Chaque dépôt
forké est un dépôt à resynchroniser à chaque release mensuelle ;
chaque modification dans un dépôt AOSP est un conflit potentiel.
Stratégie retenue, de la moins invasive à la plus invasive :

1. **Forker un seul dépôt : `platform_manifest`** (dans le compte
   GitHub de Grégoire — point d'arrêt, section 10). C'est le point
   d'entrée de `repo init` ; tout le reste découle de lui.
2. **Ajouter un `local_manifests`** (mécanisme standard de `repo`) :
   un petit fichier XML, versionné dans NOTRE dépôt de contrôle, qui
   déclare nos dépôts supplémentaires et, seulement si nécessaire,
   remplace (`remove-project` + ré-ajout) un dépôt amont par notre
   fork. Au départ : **aucun remplacement** — uniquement des ajouts.
3. **Nos applications vivent dans NOTRE dépôt**, jamais dans un
   dépôt AOSP modifié. Précédent officiel : GrapheneOS lui-même
   embarque ses applications (App Store, Auditor, Camera, PdfViewer,
   configurations GmsCompat) comme **APK précompilés dans des dépôts
   `external/`** de son manifest. On fait pareil : un dépôt
   `external/pixelbridge` (nom de travail) ajouté par le local
   manifest, qui contient nos APK précompilés et signés + leurs
   fichiers d'intégration (section 4). Construire l'APK reste le
   travail de l'atelier GitHub existant (CI, quelques minutes) ;
   le build d'OS ne fait que l'emballer.
4. **Escalade seulement si un mur l'exige** : si une capacité demande
   une modification du framework (par exemple la politique SELinux
   ou un service système), on forke alors — et seulement alors — le
   dépôt précis concerné (`system/sepolicy`, `frameworks/base`…),
   en le déclarant dans le local manifest. Chaque escalade est
   documentée ici avec le mur qui l'a forcée.

Ce que ça donne concrètement : suivre une release mensuelle de
GrapheneOS = pointer notre manifest forké sur le nouveau tag,
resynchroniser, recompiler, signer. Aucune fusion de code à faire
tant qu'on reste aux paliers 1-3 de cette stratégie.

## 4. Nos applications en priv-app : la mécanique

Pour qu'une application soit une application système privilégiée
dans un build AOSP/GrapheneOS :

- **Emplacement** : `/system/priv-app/<NomApp>/`, déclaré dans le
  build par un module `android_app_import` (APK précompilé) avec
  `privileged: true`, dans notre dépôt d'overlay. Signature : notre
  propre clé d'application dédiée suffit pour la plupart des
  permissions privilégiées ; la clé `platform` n'est requise que
  pour les permissions `signature` les plus profondes et l'identité
  `android.uid.system` — à n'utiliser que si un besoin précis le
  justifie (surface d'attaque : une app signée platform compromise
  = le système).
- **Permissions privilégiées** : toute permission
  `signature|privileged` détenue par une priv-app doit être
  **explicitement listée** dans un fichier
  `/system/etc/permissions/privapp-permissions-pixelbridge.xml`
  (mécanisme `privapp-permissions` du build). Sans cette liste
  blanche, la permission est refusée au démarrage — c'est le garde-fou
  d'Android, et c'est aussi notre inventaire officiel : ce fichier
  dit exactement ce que notre « spyware » a le droit de faire.
- **Octrois par défaut** : les permissions dangereuses (localisation,
  micro, SMS…) peuvent être pré-accordées sans dialogue utilisateur
  via `/system/etc/default-permissions/default-permissions-pixelbridge.xml`.
  C'est ce qui rend l'installation silencieuse — et c'est un choix
  d'architecture assumé, consigné ici, pas un effet de bord.
- **Exemptions système** : `/system/etc/sysconfig/` permet d'inscrire
  nos paquets dans les listes d'exemption (batterie — `allow-in-power-save`,
  données, démarrage) pour que le service de contexte ne soit pas tué
  par l'optimiseur.
- **SELinux** : signée de notre clé dédiée et déclarée dans
  `seapp_contexts` de notre overlay, l'app roule dans le domaine
  `priv_app`, qui a déjà un accès large (fournisseur de téléphonie,
  audio, localisation). Des besoins plus profonds passeraient par des
  règles dans `system/sepolicy` privé de l'overlay — escalade à
  justifier au cas par cas (section 3, palier 4).

Deux applications sont visées :

- **L'assistant (pont)** : le service de capture/contexte. C'est lui
  qui détient les permissions sensibles de la section 5.
- **Gestion** : préinstallée, citoyenne de première classe,
  alimentée directement par le pont. Sa couche locale hors ligne
  reste un chantier applicatif distinct (l'app actuelle est une web
  app sur le VPS) ; le fork lui donne le siège, pas le moteur.

## 5. Les capacités visées — le « spyware domestiqué »

### 5.0 La règle qui gouverne tout : rien en permanence

Aucune capacité de cette section n'est une capture indiscriminée et
continue. Toutes sont **gâtées par le moteur de contexte** (section 6) :
les mêmes signaux que l'activation en voiture déjà conçue au palier 1 —
téléphone **en charge**, **Bluetooth de l'auto connecté**, **vitesse
GPS** au-delà d'un seuil, **appel en cours**. Hors contexte armé, les
capteurs dorment. Et chaque ouverture de fenêtre de capture laisse une
trace dans un **journal d'audit local**, consultable par Grégoire :
domestiqué veut aussi dire *comptable envers lui*. (Sa règle
d'ergonomie s'applique : pas d'état invisible — un indicateur discret
mais réel signale qu'une capture est active.)

### 5.1 Audio d'appel direct

- **Mur palier 2** : une app ordinaire ne peut pas enregistrer le son
  d'un appel. La source audio `VOICE_CALL` (montant + descendant
  mélangés, propres, sans acoustique d'habitacle) est verrouillée ;
  au palier 1, on contourne en écoutant la pièce au micro — en
  voiture ça marche, mais c'est le haut-parleur et le bruit de route.
- **Mécanisme fork** : permission **`CAPTURE_AUDIO_OUTPUT`**
  (`signature|privileged`) listée dans notre privapp-permissions,
  détenue par l'assistant en priv-app → `AudioRecord` sur la source
  `VOICE_CALL` devient accessible. Déclenchement et état d'appel via
  `READ_PHONE_STATE` (pré-accordée) et les callbacks TelephonyManager.
- **Gâchette** : par construction, seulement pendant un appel ; le
  moteur de contexte décide en plus *quels* appels arment la chaîne
  complète (transcription/sentinelle), par exemple en voiture.

### 5.2 Textos (SMS)

- **Mur palier 2** : `READ_SMS` n'est accordée qu'au gestionnaire SMS
  par défaut (ou à une priv-app listée) ; une app ordinaire ne lit
  pas la boîte SMS des autres.
- **Mécanisme fork** : `READ_SMS` / `RECEIVE_SMS` dans le
  privapp-permissions + octroi par défaut → lecture du fournisseur
  `Telephony.Sms` au complet. Le SMS est en clair : aucun obstacle
  cryptographique.
- **Gâchette** : lecture à l'événement (arrivée d'un SMS) dans un
  contexte armé, ou balayage ciblé après un appel — pas de polling
  permanent.

### 5.3 Messenger — la limite honnête

- **Mur palier 2** : Messenger vit dans son propre sandbox ; son
  cache Messenger est fermé aux autres apps. Et surtout :
  **chiffrement de bout en bout** — les conversations sont chiffrées
  (protocole Signal) ; le contenu stocké sur l'appareil est du
  texte chiffré, pas un journal lisible.
- **Ce que le fork ne peut PAS faire** : lire la base de données de
  Messenger. Même en tordant la politique SELinux pour atteindre ses
  fichiers (escalade lourde et fragile), on obtiendrait du chiffré.
  Cette voie est écartée par conception.
- **Mécanismes fork retenus** — les deux surfaces où le contenu
  existe en clair, parce qu'un humain le voit :
  1. **Notifications** : un `NotificationListenerService` intégré à
     l'image et **pré-autorisé dans la configuration par défaut** du
     système (au lieu de l'autorisation manuelle des réglages) capte
     le texte des notifications Messenger à leur arrivée.
  2. **Accessibilité** : un service d'accessibilité **intégré au
     système et pré-activé** peut lire le contenu des fenêtres de
     Messenger quand l'app est ouverte, et observer le texte saisi
     (voir 5.4).
- **Limites honnêtes** : messages dont la notification est masquée ou
  désactivée = invisibles par la voie 1 ; messages éphémères et
  « vu sans ouvrir » restent partiels ; la voie 2 ne voit que ce qui
  est affiché à l'écran pendant que l'app est au premier plan.
- **Gâchette** : capture des notifications seulement en contexte
  armé ; lecture d'écran seulement quand Messenger est au premier
  plan dans un contexte armé.

### 5.4 Capture de ce qui est tapé — et l'exclusion dure des mots de passe

- **Mur palier 2** : aucune API ne permet à une app d'observer la
  saisie dans les autres applications.
- **Mécanismes fork possibles** :
  1. **Service d'accessibilité intégré** (recommandé en premier) :
     les événements `TYPE_VIEW_TEXT_CHANGED` livrent le texte tapé
     dans la plupart des applications. La plateforme protège déjà
     les champs de mot de passe (le texte n'y est pas livré en
     clair, le champ est marqué `isPassword`) — mais on ne s'y fie
     pas seul, voir l'exclusion ci-dessous.
  2. **Notre propre clavier (IME) intégré comme clavier système** :
     fidélité totale — il voit chaque frappe par construction.
     C'est aussi la responsabilité maximale : la plateforme ne peut
     rien masquer au clavier lui-même. À n'adopter que si la voie 1
     s'avère insuffisante.
- **EXCLUSION DURE — « pas les passwords » (contrainte d'architecture
  de Grégoire, pas une préférence)** : les mots de passe ne sont
  **jamais** captés, et l'exclusion se fait **à la source**, pas au
  moment de transmettre :
  - le capteur inspecte le type du champ **avant toute lecture ou
    mise en tampon** : `EditorInfo.inputType` dont la variation est
    `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`,
    `TYPE_NUMBER_VARIATION_PASSWORD` — et `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
    traitée comme un mot de passe aussi (c'en est un, affiché) —
    ainsi que tout nœud/événement marqué `isPassword` côté
    accessibilité → le contenu n'est **jamais lu, jamais stocké,
    jamais journalisé**. Le code de capture n'a littéralement pas de
    chemin qui accepte ces champs : ignorer d'abord, capter ensuite.
  - même discipline pour les modes « privé » signalés au clavier
    (`IME_FLAG_NO_PERSONALIZED_LEARNING` et équivalents) : pas de
    capture.
  - cet invariant est un point de revue de code nommé et un test :
    toute modification du capteur repasse par « le test password ».
- **Limite honnête** : un champ de mot de passe mal déclaré par une
  application tierce (type texte ordinaire pour un vrai mot de
  passe) échapperait au filtre par type. Risque résiduel réel, à
  assumer en connaissance de cause ; atténuations : le traitement
  reste local à l'appareil, le journal d'audit montre ce qui a été
  capté, et la voie accessibilité (où la plateforme masque déjà)
  est préférée à la voie clavier.
- **Gâchette** : saisie captée seulement dans les applications et
  contextes armés (jamais en tâche de fond permanente toutes apps
  confondues).

### 5.5 GPS discret

- **Mur palier 2** : la localisation en arrière-plan exige un octroi
  utilisateur révocable (`ACCESS_BACKGROUND_LOCATION`) assorti
  d'avertissements ; l'utilisateur peut l'éteindre à tout moment, et
  le système rationne les positions en arrière-plan.
- **Mécanisme fork** : `ACCESS_FINE_LOCATION` +
  `ACCESS_BACKGROUND_LOCATION` pré-accordées à l'assistant
  (default-permissions) + exemption batterie (sysconfig) → positions
  à la demande du moteur de contexte, sans dialogue ni révocation
  accidentelle. C'est le prolongement du suivi kilométrique de
  Gestion : détection et journalisation des déplacements en
  coulisses, lectures d'odomètre comme ancres de calibration.
- **Limite honnête** : Android affiche un indicateur quand la
  localisation est consultée ; le rendre totalement invisible
  exigerait de patcher SystemUI/le framework — chirurgie plus
  profonde, non retenue. « Discret » = pas de dialogue, pas de
  friction ; pas « indétectable par le propriétaire », ce qui serait
  contraire au principe du journal d'audit.
- **Gâchette** : échantillonnage lié au contexte (charge + Bluetooth
  auto = armer ; vitesse = confirmer le mouvement) — le même
  détecteur de conduite que le kilométrage. Jamais de pistage
  continu hors contexte.

### 5.6 Courriels

- **Mur palier 2** : même sandbox que Messenger — le contenu local
  de l'app Gmail est fermé aux autres apps (sans chiffrement de
  bout en bout, mais derrière le sandbox et les jetons du compte).
- **Mécanismes fork, par ordre de préférence** :
  1. **Un composant de synchronisation à nous dans le fork** (le
     modèle K-9/Thunderbird intégré au système) : l'assistant
     synchronise lui-même les boîtes de Grégoire en IMAP, identifiants
     dans le keystore matériel. Contenu complet, en clair, sans
     grattage — et compatible avec la contrainte fondatrice hors
     ligne (le courrier vit sur l'appareil).
  2. **Notifications + accessibilité** (comme Messenger, 5.3) pour
     les apps de courriel tierces utilisées telles quelles.
  3. Rappel : côté serveur, Muse lit déjà son Gmail par connecteur ;
     la copie sur l'appareil sert l'autonomie hors ligne, pas
     l'accès lui-même.
- **Voie écartée** : lire la base locale de Gmail — sandbox, même
  impasse qu'en 5.3, sans même l'excuse du chiffrement : fragile et
  indigne de l'effort.
- **Gâchette** : fenêtres de synchronisation liées au contexte et au
  réseau (charge, réseau revenu), pas de push permanent toutes
  boîtes en tous contextes.

### 5.7 Armement automatique et survie du service

- **Mur palier 2** : une app ordinaire doit être lancée à la main,
  ses écoutes d'arrière-plan sont rationnées ou tuées, et le
  démarrage automatique après reboot est limité.
- **Mécanisme fork** : récepteur `BOOT_COMPLETED` + service au
  premier plan déclaré dans l'image + exemptions sysconfig
  (batterie/données) → le moteur de contexte démarre avec le
  téléphone et y reste. C'est la brique qui rend toutes les autres
  utilisables au quotidien : personne n'aura à « ouvrir l'assistant »
  avant de prendre la route.

### 5.8 Récapitulatif

| Capacité | Mur du palier 2 | Mécanisme fork | Gâchette de contexte |
|---|---|---|---|
| Audio d'appel direct | Source `VOICE_CALL` verrouillée | `CAPTURE_AUDIO_OUTPUT` en priv-app | Appel en cours (+ contexte) |
| SMS | `READ_SMS` réservée au défaut/priv-app | Privapp-permissions + octroi par défaut | Événement, contexte armé |
| Messenger | Sandbox + bout-en-bout | Notifications pré-autorisées + accessibilité système | Contexte armé ; app au premier plan |
| Saisie clavier | Aucune API d'observation | Accessibilité intégrée (ou IME maison) — **passwords exclus à la source** | Apps et contextes armés |
| GPS | Octroi révocable, rationnement | Octrois par défaut + exemption batterie | Charge + Bluetooth auto + vitesse |
| Courriels | Sandbox Gmail | Synchro IMAP intégrée (identifiants au keystore) | Fenêtres charge/réseau |
| Armement auto | Arrière-plan tué/rationné | Service système + sysconfig + boot | Permanent — c'est lui la gâchette |

## 6. Le moteur de contexte

Un seul composant décide, pour toutes les capacités : le détecteur
de contexte, partagé avec le kilométrage de Gestion (un seul
détecteur de conduite pour les deux). Cascade de signaux, du moins
cher au plus cher : **charge** (presque gratuit, non ambigu) et
**Bluetooth de l'auto** arment le mode ; le **GPS/vitesse** confirme
le mouvement ; l'**état d'appel** ouvre et ferme les fenêtres
d'écoute. États explicites (armé / en capture / au repos), journal
d'audit local horodaté de chaque transition et de chaque fenêtre de
capture. Hors contexte : tout dort, y compris l'oreille.

## 7. Machine de build

Exigences rappelées : Linux x86_64, **32 Gio de RAM minimum**
(64 visés), ~250 Go utiles au bas mot entre arbre de sources
(136 Gio), `out/` et releases — 500 Go libres = serein. Trois
options OVHcloud dédiées, **prix vérifiés le 2026-11-17 sur
ovhcloud.com/en-ca, en dollars canadiens, hors taxes** (frais
d'installation uniques du même montant que le premier mois) :

| Option | CPU | RAM | Stockage | Prix |
|---|---|---|---|---|
| **RISE-S** (gamme Eco) | Ryzen 7 9700X, 8 c / 16 t, 3,8/5,5 GHz | 64 Go DDR5 | 2× 512 Go NVMe (RAID logiciel) | **103,99 $/mois** |
| **ADVANCE-2 2024** | EPYC 4344P, 8 c / 16 t, 3,8/5,3 GHz | 64 Go DDR5 ECC | 2× 960 Go NVMe (RAID logiciel) | **247,99 $/mois** |
| **ADVANCE-3 2024** | EPYC 4464P, 12 c / 24 t, 3,7/5,4 GHz | 64 Go DDR5 ECC | 2× 960 Go NVMe (RAID logiciel) | **319,99 $/mois** |

Lecture :

- **RISE-S** est l'option rationnelle pour commencer : le CPU le
  plus rapide en cadence des trois, 64 Go, ~1 To NVMe brut (512 Go
  utiles en RAID1 — suffisant pour l'arbre + un build, juste si on
  empile les releases ; RAID0 ou discipline de ménage règlent ça).
  Builds estimés dans le haut de la fourchette de la section 2.4.
- **ADVANCE-2** achète du stockage datacenter (1,9 To brut) et la
  gamme « sérieuse » d'OVH (bande passante garantie supérieure),
  au double du prix, sans aller plus vite en build.
- **ADVANCE-3** est l'option « builds fréquents » : 24 fils coupent
  le temps de build complet de façon tangible (~1,5-3 h estimées).
  Surdimensionné si on compile une fois par mois.
- Centre de données : OVH a Beauharnois (BHS) au Québec — le
  candidat naturel (compte existant, proximité). La disponibilité
  exacte par gamme se confirme à la commande.
- Modèle d'usage : Muse pilote le build en SSH (comme le VPS
  actuel) ; Grégoire ne touche pas à Linux. La machine peut être
  **résiliée entre les releases** si le rythme mensuel ne justifie
  pas le loyer permanent — mais l'arbre de sources (136 Gio) et les
  target-files signés (nécessaires aux deltas) doivent alors vivre
  quelque part entre les sessions ; à trancher selon le rythme réel.
- **Option zéro — son vieux PC** : toujours ouverte. Les critères
  sont connus (section « PC âgé » du dossier de projet) : la RAM est
  LE facteur (32 Go = plancher officiel viable, 64 = confort) ; un
  CPU âgé allonge le build, ce qui est acceptable pour des builds
  rares et nocturnes ; les vrais bloqueurs possibles sont un disque
  mécanique ou moins de ~400 Go libres — réparables à peu de frais
  (SSD 1 To, barrettes DDR4). Il manque encore ses specs exactes
  (processeur, mémoire installée, espace libre) pour trancher ; le
  jour où elles arrivent, cette option peut rendre la location
  inutile.

## 8. Le compromis Play Integrity — dit franchement

C'est LE prix du fork, et il ne sera pas tranché dans ce document.

- **Aujourd'hui, sur GrapheneOS officiel** : l'app Desjardins
  installée depuis le Play Store sandboxé fonctionne — connexion
  réussie, Play Integrity ne bloque pas (vérifié en vrai le
  2026-11-16). Android Auto sans fil fonctionne aussi. C'est
  l'état de référence, et il est bon.
- **Ce qui change avec le fork** : le système n'est plus signé par
  les clés de GrapheneOS, mais par les nôtres. Le démarrage vérifié
  reste entier (notre clé AVB est enrôlée dans le téléphone au
  reverrouillage), mais l'**attestation d'intégrité** que les
  applications demandent à Google ne voit plus « GrapheneOS
  officiel » : elle voit un build signé par des clés inconnues de
  Google. Les applications qui exigent un verdict d'intégrité fort
  (banques, paiement sans contact) peuvent alors refuser de
  fonctionner — ou fonctionner ; **ce n'est pas prévisible sur
  papier, application par application.**
- **Le risque concret** : perdre Desjardins (et possiblement
  d'autres apps à vérification) sur le téléphone forké, alors
  qu'elles passent sur l'officiel. Google Pay sans contact est déjà
  hors jeu sur GrapheneOS officiel ; ce n'est pas une perte
  additionnelle du fork.
- **Ce qui ne change pas** : Play Store sandboxé, GmsCompat et
  Android Auto sont du code, hérités tels quels par le fork (voir
  l'analyse du 2026-11-14 au dossier) — le risque y est faible
  mais, là aussi, seule l'épreuve le dira.
- **Atténuations possibles** : (1) le Pixel actuel est un appareil
  de labo — le fork s'y installe et s'y éprouve (Desjardins et
  Android Auto en tête de la batterie de tests) **avant** toute
  idée d'en faire le téléphone quotidien ; (2) le chemin de retour
  vers l'officiel existe et est documenté (section 10) ; (3) si
  une app critique casse, le repli « officiel + intégration
  palier 2 » reste viable en permanence.
- **Décision réservée à Grégoire**, après l'épreuve, pas avant.

## 9. Stratégie de mises à jour

Deux régimes possibles, pas exclusifs :

- **Builds manuels (démarrage)** : à chaque release mensuelle de
  GrapheneOS (les correctifs de sécurité Android sont mensuels),
  on resynchronise sur le nouveau tag, on recompile, on signe, et
  on flashe/sideload le paquet OTA à la main. Simple, aucun
  serveur à maintenir ; le coût est une séance supervisée par mois.
- **Serveur OTA maison (régime de croisière)** : GrapheneOS fournit
  l'app **Updater** et un modèle de serveur **statique** : le
  script de release génère le paquet OTA, les images d'usine
  signées et les métadonnées de canaux (`husky-stable`, `husky-beta`,
  `husky-testing`) ; il suffit d'héberger ces fichiers. Les deltas
  incrémentaux se génèrent avec `script/generate-delta.sh` à partir
  des target-files signés conservés.
  **Obligation absolue, écrite dans la documentation officielle** :
  si `OFFICIAL_BUILD=true` (pour inclure Updater), il faut changer
  l'URL dans `packages/apps/Updater/res/values/config.xml` pour
  NOTRE serveur. Pointer un build signé de nos clés vers le serveur
  officiel ferait télécharger des mises à jour non signées de nos
  clés, rejetées en boucle — la doc le qualifie d'attaque par déni
  de service contre leur service. Notre URL = un sous-domaine à
  nous, servi en statique (le VPS actuel peut l'héberger : ce ne
  sont que des fichiers).
- **Cadence et discipline** : suivre les tags stables mensuels ;
  canal `testing` d'abord sur l'appareil de labo, `stable` quand
  l'épreuve est passée. Un correctif de sécurité critique peut
  justifier un build hors cycle.
- **Ce qu'il faut conserver** : les target-files signés de chaque
  release (pour les deltas) et, évidemment, les clés (section 2.3).

## 10. Flash sur SON Pixel 8 Pro — et le chemin de retour

Procédure du premier flash (détail opérationnel dans
`fork/README.md`) :

1. Sauvegarde de l'état officiel : le passage au fork **efface
   l'appareil** (déverrouillage du bootloader = réinitialisation).
   À ce stade du projet, le Pixel est un appareil de labo sans
   données irremplaçables — c'est le bon moment.
2. Déverrouiller le bootloader (le « Déverrouillage OEM » a été
   vérifié activable le 2026-11-14 ; il est présentement éteint et
   devra être réactivé pour l'opération).
3. Flasher les images d'usine de notre build (le script de flash
   fourni dans le zip factory, via fastboot, depuis la machine de
   build ou le Mac de Grégoire).
4. **Reverrouiller le bootloader avec notre clé AVB**
   (`avb_pkmd.bin`) : le démarrage vérifié repart, cette fois sur
   nos signatures. Réactiver ensuite la discipline actuelle :
   Déverrouillage OEM éteint, options développeur fermées.
5. Batterie d'épreuve immédiate, avant tout usage réel :
   Desjardins, Android Auto sans fil, appels, Play sandboxé —
   exactement les tests du 2026-11-16, rejoués sur le fork.

**Chemin de retour vers l'officiel — il existe et il est simple** :
l'installateur web officiel de GrapheneOS (ou les images d'usine
officielles) reflashe un système signé des clés officielles, puis
reverrouillage sur la clé officielle. Le Pixel redevient un
GrapheneOS officiel à l'identique d'aujourd'hui. Les **données** ne
survivent à aucun de ces allers-retours (chaque changement de clé =
réinitialisation) : le fork se juge sur un appareil dont on accepte
l'effacement, et les données métier vivent de toute façon côté
Gestion/serveur. Aucune étape du flash n'est irréversible pour le
matériel ; ce qui serait irréversible, c'est de perdre nos clés
(perte = retour forcé à zéro, section 2.3).

Note d'état des outils : le script `fork/generer-cles.sh` est
**non testé** — il ne pourra l'être que sur la machine de build, le
jour J, sous supervision.

## 11. Risques et inconnues (liste honnête)

- **Play Integrity** (section 8) : le risque nº 1, non prévisible
  sur papier. Desjardins est le test qui compte.
- **Android Auto sur build signé maison** : le code est hérité,
  l'attestation pourrait distinguer nos clés ; improuvable avant
  l'épreuve (analyse du 2026-11-14).
- **Charge de maintenance** : un build par mois, à perpétuité, tant
  que le fork est le système du téléphone. Si la cadence devient un
  fardeau, le téléphone vieillit en correctifs de sécurité — le
  repli officiel redevient alors la bonne réponse.
- **Surface d'attaque** : une priv-app à nous, richement permise,
  est une cible de valeur. Discipline : le moins de code possible
  dans le composant privilégié, le capteur séparé de l'interprète,
  l'invariant « passwords » testé, et le fichier privapp-permissions
  comme inventaire relu à chaque release.
- **Dépendance aux tags GrapheneOS** : si GrapheneOS change sa
  structure de manifest ou ses scripts, le mois concerné coûte plus
  cher. La surface minimale (section 3) est précisément l'assurance
  contre ça.
- **Ce plan ne décide pas** : ni la machine (section 7), ni le
  basculement du téléphone quotidien (section 8). Il rend les deux
  décisions exécutables le jour où Grégoire les prend.
