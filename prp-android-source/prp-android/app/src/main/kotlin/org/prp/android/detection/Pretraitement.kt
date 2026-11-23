package org.prp.android.detection

/** Opérations sur les pixels de l'écran capturé (tableaux ARGB). Code pur. */
object Pretraitement {

    /** Pixels ARGB vers trois plans R, G, B normalisés de 0 à 1 (ordre attendu par le modèle). */
    fun versCHW(pixels: IntArray, l: Int, h: Int): FloatArray {
        val plan = l * h
        require(pixels.size >= plan)
        val sortie = FloatArray(3 * plan)
        for (i in 0 until plan) {
            val p = pixels[i]
            sortie[i] = ((p shr 16) and 255) / 255f
            sortie[plan + i] = ((p shr 8) and 255) / 255f
            sortie[2 * plan + i] = (p and 255) / 255f
        }
        return sortie
    }

    private fun luminance(p: Int): Int =
        (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000

    /** Empreinte grossière de l'image (luminance d'une grille), pour repérer un écran qui n'a pas changé. */
    fun signature(pixels: IntArray, l: Int, h: Int, colonnes: Int = 24, lignes: Int = 48): IntArray {
        val s = IntArray(colonnes * lignes)
        for (j in 0 until lignes) {
            val y = ((j + 0.5f) * h / lignes).toInt().coerceIn(0, h - 1)
            for (i in 0 until colonnes) {
                val x = ((i + 0.5f) * l / colonnes).toInt().coerceIn(0, l - 1)
                s[j * colonnes + i] = luminance(pixels[y * l + x])
            }
        }
        return s
    }

    /** Écart moyen entre deux empreintes, de 0 (identiques) à 1. Renvoie 1 si l'une manque. */
    fun difference(a: IntArray?, b: IntArray?): Float {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return 1f
        var somme = 0L
        for (i in a.indices) somme += kotlin.math.abs(a[i] - b[i])
        return somme.toFloat() / (a.size * 255f)
    }

    /** Luminance moyenne (0 à 255) sur une grille : sert à reconnaître un écran entièrement noir. */
    fun luminanceMoyenne(pixels: IntArray, l: Int, h: Int): Float {
        val s = signature(pixels, l, h, 16, 32)
        return s.sum().toFloat() / s.size
    }

    fun couleurProche(pixel: Int, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        kotlin.math.abs(((pixel shr 16) and 255) - r) <= tolerance &&
            kotlin.math.abs(((pixel shr 8) and 255) - g) <= tolerance &&
            kotlin.math.abs((pixel and 255) - b) <= tolerance
}
