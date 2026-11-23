package org.prp.android.detection

import kotlin.math.max
import kotlin.math.min

/** Rectangle en coordonnées d'écran normalisées (0 à 1) : gauche, haut, droite, bas. */
data class Zone(val g: Float, val h: Float, val d: Float, val b: Float) {
    val aire: Float get() = (d - g) * (b - h)
    fun intersecte(o: Zone): Boolean = g < o.d && o.g < d && h < o.b && o.h < b
    fun union(o: Zone): Zone = Zone(min(g, o.g), min(h, o.h), max(d, o.d), max(b, o.b))

    companion object {
        val PLEIN = Zone(0f, 0f, 1f, 1f)
    }
}

/**
 * Décide quelles zones de l'écran sont masquées à un instant donné.
 * Chaque zone détectée est agrandie (le contexte d'une scène dépasse la partie détectée),
 * fusionnée avec ses voisines, et maintenue [tenueMs] après sa dernière confirmation :
 * le masque ne clignote donc pas entre deux analyses. Si la scène est étendue (au moins
 * [nbMax] zones séparées, ou plus de [aireMax] de l'écran), tout l'écran est masqué.
 * Code pur : le temps est fourni par l'appelant.
 */
class ControleurMasque(
    var tenueMs: Long = 2500L,
    private val aireMax: Float = 0.30f,
    private val nbMax: Int = 3,
) {
    private class Entree(var zone: Zone, var expire: Long)

    private val entrees = ArrayList<Entree>()

    /** Intègre les détections d'une image. Renvoie le nombre de boîtes retenues. */
    fun mettreAJour(
        boites: List<Boite>, largeur: Float, hauteur: Float,
        sensibilite: Int, maintenant: Long,
    ): Int {
        var retenues = 0
        for (b in boites) {
            if (!Classes.visee(b.classe) || b.score < Classes.seuil(b.classe, sensibilite)) continue
            retenues++
            val mx = max(0.35f * b.l / largeur, 0.03f)
            val my = max(0.35f * b.h / hauteur, 0.02f)
            val z = Zone(
                (b.x / largeur - mx).coerceIn(0f, 1f),
                (b.y / hauteur - my).coerceIn(0f, 1f),
                (b.droite / largeur + mx).coerceIn(0f, 1f),
                (b.bas / hauteur + my).coerceIn(0f, 1f),
            )
            ajouter(z, maintenant + tenueMs)
        }
        return retenues
    }

    private fun ajouter(z: Zone, expire: Long) {
        var courante = z
        var exp = expire
        var fusion = true
        while (fusion) {
            fusion = false
            val it = entrees.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.zone.intersecte(courante)) {
                    courante = courante.union(e.zone)
                    exp = max(exp, e.expire)
                    it.remove()
                    fusion = true
                }
            }
        }
        entrees.add(Entree(courante, exp))
    }

    /** Zones à masquer à l'instant [maintenant] (liste vide = rien à masquer). */
    fun actives(maintenant: Long): List<Zone> {
        entrees.removeAll { it.expire <= maintenant }
        if (entrees.isEmpty()) return emptyList()
        val total = entrees.sumOf { it.zone.aire.toDouble() }.toFloat()
        if (entrees.size >= nbMax || total >= aireMax) return listOf(Zone.PLEIN)
        return entrees.map { it.zone }
    }

    fun aDesZones(maintenant: Long): Boolean = actives(maintenant).isNotEmpty()

    fun effacer() = entrees.clear()
}
