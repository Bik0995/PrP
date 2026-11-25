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
     * Score de l'image : pornographie et hentaï. La catégorie « Sexy » (maillots, sous-vêtements,
     * torses nus) ne compte pas, sauf en mode enfant ; en sensibilité stricte, elle compte à moitié.
     */
    fun score(p: FloatArray, sensibilite: Int, enfant: Boolean): Float {
        val poidsSexy = when {
            enfant -> 1f
            sensibilite == 0 -> 0.5f
            else -> 0f
        }
        return p[PORNO] + p[HENTAI] + poidsSexy * p[SEXY]
    }

    /** Sensibilité : 0 = stricte (floute plus facilement), 1 = normale, 2 = souple. */
    fun seuil(sensibilite: Int): Float = when (sensibilite) { 0 -> 0.45f; 2 -> 0.75f; else -> 0.60f }

    fun aFlouter(p: FloatArray, sensibilite: Int, enfant: Boolean): Boolean =
        score(p, sensibilite, enfant) >= seuil(sensibilite)

    /** Catégorie la plus probable, pour l'écran « Détails ». */
    fun resume(p: FloatArray): String {
        var i = 0
        for (k in 1 until NB) if (p[k] > p[i]) i = k
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
