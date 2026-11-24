package org.prp.android.detection

/**
 * Choix de l'image (le « cadre ») à masquer entière pour une détection donnée.
 * Code pur : les cadres sont des rectangles en pixels d'écran (Zone utilisée en pixels).
 */
object Cadres {
    /** Un cadre plus grand que cette part de l'écran n'est pas une image mais une page entière. */
    const val AIRE_MAX = 0.92f

    /**
     * Renvoie l'indice du plus petit cadre qui contient le centre de la boîte, qui est au moins
     * à moitié aussi grand qu'elle et qui n'occupe pas tout l'écran ; -1 si aucun ne convient.
     */
    fun choisir(boite: Zone, cadres: List<Zone>, aireEcran: Float): Int {
        val cx = (boite.g + boite.d) / 2f
        val cy = (boite.h + boite.b) / 2f
        var meilleur = -1
        var aireMeilleur = Float.MAX_VALUE
        cadres.forEachIndexed { i, c ->
            val a = c.aire
            if (a <= 0f || a > AIRE_MAX * aireEcran) return@forEachIndexed
            if (cx < c.g || cx > c.d || cy < c.h || cy > c.b) return@forEachIndexed
            if (a < 0.5f * boite.aire) return@forEachIndexed
            if (a < aireMeilleur) { meilleur = i; aireMeilleur = a }
        }
        return meilleur
    }

    /** Vrai si le centre de la boîte tombe dans le cadre. */
    fun contientCentre(cadre: Zone, boite: Zone): Boolean {
        val cx = (boite.g + boite.d) / 2f
        val cy = (boite.h + boite.b) / 2f
        return cx >= cadre.g && cx <= cadre.d && cy >= cadre.h && cy <= cadre.b
    }
}
