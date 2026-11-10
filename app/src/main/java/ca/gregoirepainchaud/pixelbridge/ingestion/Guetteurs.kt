package ca.gregoirepainchaud.pixelbridge.ingestion

import ca.gregoirepainchaud.pixelbridge.data.ElementAudio

/**
 * Un guetteur surveille une source et dépose chaque nouvel élément
 * audio dans la file via [onNouvelElement]. Aucun guetteur ne traite :
 * il capte, c'est tout — le pipeline fait le reste, hors ligne.
 */
interface Guetteur {
    fun demarrer(onNouvelElement: (ElementAudio) -> Unit)
    fun arreter()
}

/**
 * Appels téléphoniques.
 * À valider sur l'appareil : le mécanisme exact dépend de la ROM
 * (enregistreur d'appels de la ROM, dossier d'enregistrements surveillé,
 * ou accès root au flux). Aucune hypothèse n'est codée ici.
 */
class GuetteurAppels : Guetteur {
    override fun demarrer(onNouvelElement: (ElementAudio) -> Unit) {
        // TODO appareil : brancher la source d'enregistrements d'appels.
    }

    override fun arreter() {}
}

/**
 * Notes vocales WhatsApp et autres apps qui déposent de vrais fichiers
 * audio (.opus) dans leur dossier média.
 * Mécanisme prévu : FileObserver sur les dossiers de notes vocales.
 */
class GuetteurNotesVocales : Guetteur {
    override fun demarrer(onNouvelElement: (ElementAudio) -> Unit) {
        // TODO appareil : FileObserver sur les dossiers « Voice Notes ».
    }

    override fun arreter() {}
}

/**
 * Messages vocaux Messenger : le clip vit dans le cache privé de l'app.
 * Mécanisme prévu (root) : balayage du cache Messenger, copie des
 * nouveaux clips. Repli si le cache devient inaccessible : capture
 * audio interne pendant la lecture du message.
 * Le morceau le plus fragile — à tester tôt sur l'appareil réel.
 */
class GuetteurMessenger : Guetteur {
    override fun demarrer(onNouvelElement: (ElementAudio) -> Unit) {
        // TODO appareil : balayage du cache Messenger (root requis).
    }

    override fun arreter() {}
}
