package ca.gregoirepainchaud.pixelbridge.data

/**
 * File locale des éléments. Tout le pipeline lit et écrit ici ; rien
 * ne dépend du réseau.
 *
 * SQUELETTE : implémentation en mémoire pour poser les contrats.
 * À brancher : persistance SQLite (Room) — les éléments doivent
 * survivre au redémarrage de l'appareil, c'est la file hors ligne.
 */
class FileTraitement {

    private val elements = mutableListOf<ElementAudio>()

    @Synchronized
    fun ajouter(element: ElementAudio) {
        if (elements.none { it.id == element.id }) elements.add(element)
    }

    @Synchronized
    fun parStatut(statut: StatutElement): List<ElementAudio> =
        elements.filter { it.statut == statut }

    @Synchronized
    fun mettreAJour(element: ElementAudio) {
        val i = elements.indexOfFirst { it.id == element.id }
        if (i >= 0) elements[i] = element
    }

    @Synchronized
    fun comptes(): Map<StatutElement, Int> =
        elements.groupingBy { it.statut }.eachCount()
}
