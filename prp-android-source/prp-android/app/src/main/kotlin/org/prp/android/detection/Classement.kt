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
     * Deux modèles indépendants doivent être d'accord :
     *  - NSFWJS (MIT) : la catégorie la plus probable doit être Porn ou Hentai (Sexy en plus en
     *    mode enfant), avec un score suffisant ;
     *  - Marqo nsfw-image-detection-384 (Apache 2.0) : probabilité « NSFW » élevée.
     * Une photo de sport ou de plage peut tromper l'un des deux, rarement les deux à la fois.
     * Sensibilité : 0 = stricte, 1 = normale, 2 = souple.
     */
    fun seuilNsfwjs(sensibilite: Int): Float = when (sensibilite) { 0 -> 0.50f; 2 -> 0.75f; else -> 0.60f }

    fun seuilMarqo(sensibilite: Int): Float = when (sensibilite) { 0 -> 0.75f; 2 -> 0.92f; else -> 0.85f }

    /** Pornographie (ou hentaï) selon NSFWJS. */
    private fun explicite(p: FloatArray, sensibilite: Int): Boolean {
        val d = dominante(p)
        return (d == PORNO || d == HENTAI) && p[PORNO] + p[HENTAI] >= seuilNsfwjs(sensibilite)
    }

    /** Suggestif selon NSFWJS (mode enfant seulement). */
    private fun suggestif(p: FloatArray): Boolean = dominante(p) == SEXY && p[SEXY] >= 0.5f

    /** Premier tri, avec NSFWJS seul : faut-il demander l'avis de Marqo ? */
    fun suspect(p: FloatArray, sensibilite: Int, enfant: Boolean): Boolean =
        explicite(p, if (enfant) 0 else sensibilite) || (enfant && suggestif(p))

    /**
     * Décision finale. [marqo] : probabilité « NSFW » de Marqo. Un morceau d'écran ([tuile])
     * demande un peu plus de certitude, car il coupe souvent les personnes.
     */
    fun aFlouter(p: FloatArray, marqo: Float, sensibilite: Int, enfant: Boolean, tuile: Boolean = false): Boolean {
        val bonus = if (tuile) 0.05f else 0f
        val sens = if (enfant) 0 else sensibilite
        if (explicite(p, sens) && marqo >= (seuilMarqo(sens) + bonus).coerceAtMost(0.97f)) return true
        // mode enfant : le suggestif aussi, mais seulement si Marqo le trouve nettement osé
        return enfant && suggestif(p) && marqo >= 0.75f + bonus
    }

    /** Catégorie la plus probable. */
    fun dominante(p: FloatArray): Int {
        var i = 0
        for (k in 1 until NB) if (p[k] > p[i]) i = k
        return i
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
        // deuxième grille décalée d'une demi-case : une personne coupée par la première
        // grille est entière dans celle-ci
        for (j in 0 until lignes - 1)
            res.add(Zone(tl / 2, th / 2 + j * th, tl / 2 + tl, th / 2 + (j + 1) * th))
        return res
    }
}
