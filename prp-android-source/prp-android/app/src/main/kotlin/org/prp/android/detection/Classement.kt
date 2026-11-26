package org.prp.android.detection

/**
 * Décisions du classificateur d'images (modèle NSFWJS MobileNetV2, licence MIT).
 * Le modèle donne, pour une image entière, la probabilité de cinq catégories.
 * Code pur : testé sur n'importe quelle machine.
 */
object Classement {
    const val DESSIN = 0
    const val HENTAI = 1
    const val NEUTRE = 2
    const val PORNO = 3
    const val SEXY = 4
    val NOMS = arrayOf("Drawing", "Hentai", "Neutral", "Porn", "Sexy")
    const val NB = 5
    const val COTE = 224

    /**
     * Score de l'image : pornographie et hentaï seulement. « Sexy » (maillots, sous-vêtements,
     * peau nue sans acte sexuel : bras, jambes, pieds, torses) ne compte qu'en mode enfant.
     */
    fun score(p: FloatArray, enfant: Boolean): Float =
        p[PORNO] + p[HENTAI] + (if (enfant) p[SEXY] else 0f)

    /** Sensibilité : 0 = stricte, 1 = normale, 2 = souple. Seuils hauts : la peau seule ne suffit pas. */
    fun seuil(sensibilite: Int): Float = when (sensibilite) { 0 -> 0.65f; 2 -> 0.90f; else -> 0.80f }

    /** Catégorie la plus probable. */
    fun dominante(p: FloatArray): Int {
        var i = 0
        for (k in 1 until NB) if (p[k] > p[i]) i = k
        return i
    }

    /**
     * Floute seulement si la catégorie la plus probable est elle-même visée (Porn ou Hentai ;
     * Sexy en plus en mode enfant) ET que le score dépasse le seuil. Un morceau d'écran
     * ([tuile]) demande un peu plus de certitude, car il coupe souvent les personnes.
     */
    fun aFlouter(p: FloatArray, sensibilite: Int, enfant: Boolean, tuile: Boolean = false): Boolean {
        val d = dominante(p)
        val visee = d == PORNO || d == HENTAI || (enfant && d == SEXY)
        if (!visee) return false
        val s = (seuil(sensibilite) + if (tuile) 0.05f else 0f).coerceAtMost(0.97f)
        return score(p, enfant) >= s
    }

    /** Catégorie la plus probable, pour l'écran « Détails ». */
    fun resume(p: FloatArray): String {
        val i = dominante(p)
        return "${NOMS[i]} ${(p[i] * 100).toInt()}%"
    }

    /** Une image trop petite (icône, avatar) n'est pas analysée. */
    const val COTE_MIN = 72f

    /**
     * Images à analyser : assez grandes, pas plus grandes que l'écran entier, les plus grandes
     * d'abord. Renvoie leurs indices.
     */
    fun candidats(zones: List<Zone>, lEcran: Float, hEcran: Float): List<Int> =
        zones.indices
            .filter {
                val z = zones[it]
                z.l >= COTE_MIN && z.ht >= COTE_MIN && z.aire <= 1.02f * lEcran * hEcran
            }
            .sortedByDescending { zones[it].aire }

    /**
     * Quand l'application ne déclare aucune image (vue dessinée par elle-même) : l'écran est
     * découpé en [colonnes] x [lignes] morceaux, chacun analysé comme une image.
     */
    fun tuiles(lEcran: Float, hEcran: Float, colonnes: Int = 2, lignes: Int = 3): List<Zone> {
        val res = ArrayList<Zone>()
        val tl = lEcran / colonnes
        val th = hEcran / lignes
        for (j in 0 until lignes) for (i in 0 until colonnes)
            res.add(Zone(i * tl, j * th, (i + 1) * tl, (j + 1) * th))
        return res
    }
}
