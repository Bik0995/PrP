package org.prp.android.detection

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Boîte détectée, en pixels de l'image d'origine (x, y = coin haut-gauche). */
data class Boite(
    val x: Float, val y: Float, val l: Float, val h: Float,
    val classe: Int, val score: Float,
) {
    val droite: Float get() = x + l
    val bas: Float get() = y + h
}

/**
 * Pré- et post-traitement du modèle (YOLOv8, sortie [1, 22, N] : 4 coordonnées + 18 classes).
 * Code pur, sans dépendance Android : il suit exactement le code de référence de NudeNet.
 */
object Yolo {
    const val SEUIL_CANDIDAT = 0.25f
    const val SEUIL_IOU = 0.45f
    /** Une « poitrine de femme » dont le score « torse d'homme » atteint cette part est un torse d'homme. */
    const val RAPPORT_HOMME = 0.5f

    /**
     * Taille d'entrée du modèle pour un écran donné : le petit côté vaut [court], le grand côté
     * est un multiple de 32 (exigé par le modèle), plafonné à [maxLong]. Le modèle accepte
     * toute taille multiple de 32, donc l'écran n'est ni déformé ni entouré de bandes.
     */
    fun dimensionsEntree(largeur: Int, hauteur: Int, court: Int = 320, maxLong: Int = 800): Pair<Int, Int> {
        require(largeur > 0 && hauteur > 0)
        val petit = min(largeur, hauteur)
        val grand = max(largeur, hauteur)
        val long = ((court.toDouble() * grand / petit / 32.0).roundToInt() * 32).coerceIn(court, maxLong)
        return if (largeur <= hauteur) Pair(court, long) else Pair(long, court)
    }

    /**
     * Décode la sortie brute du modèle. [sortie] est rangée par canal : la valeur du canal c
     * pour l'ancre i se trouve à l'indice c * ancres + i.
     */
    fun decoder(
        sortie: FloatArray, ancres: Int,
        largeurModele: Int, hauteurModele: Int,
        largeurImage: Float, hauteurImage: Float,
    ): List<Boite> {
        val canaux = 4 + Classes.NB
        require(ancres > 0 && sortie.size >= canaux * ancres) { "sortie trop courte" }
        val sx = largeurImage / largeurModele
        val sy = hauteurImage / hauteurModele
        val candidats = ArrayList<Boite>()
        for (i in 0 until ancres) {
            var meilleure = 0
            var score = -1f
            for (c in 0 until Classes.NB) {
                val v = sortie[(4 + c) * ancres + i]
                if (v > score) { score = v; meilleure = c }
            }
            if (score < SEUIL_CANDIDAT) continue
            // Torse d'homme pris pour une poitrine de femme : si le modèle hésite, on retient l'homme.
            if (meilleure == Classes.POITRINE_F && sortie[(4 + Classes.POITRINE_H) * ancres + i] >= score * RAPPORT_HOMME)
                meilleure = Classes.POITRINE_H
            val cx = sortie[i]
            val cy = sortie[ancres + i]
            val w = sortie[2 * ancres + i]
            val h = sortie[3 * ancres + i]
            val x = ((cx - w / 2) * sx).coerceIn(0f, largeurImage)
            val y = ((cy - h / 2) * sy).coerceIn(0f, hauteurImage)
            val bl = min(w * sx, largeurImage - x)
            val bh = min(h * sy, hauteurImage - y)
            if (bl <= 0f || bh <= 0f) continue
            candidats.add(Boite(x, y, bl, bh, meilleure, score))
        }
        return nms(candidats)
    }

    /** Suppression des doublons (toutes classes confondues, comme la référence). */
    fun nms(boites: List<Boite>, seuilIou: Float = SEUIL_IOU): List<Boite> {
        val triees = boites.sortedByDescending { it.score }
        val gardees = ArrayList<Boite>()
        for (b in triees) {
            if (gardees.none { iou(it, b) > seuilIou }) gardees.add(b)
        }
        return gardees
    }

    fun iou(a: Boite, b: Boite): Float {
        val gx = max(a.x, b.x)
        val gy = max(a.y, b.y)
        val dx = min(a.droite, b.droite)
        val dy = min(a.bas, b.bas)
        val inter = max(0f, dx - gx) * max(0f, dy - gy)
        val union = a.l * a.h + b.l * b.h - inter
        return if (union <= 0f) 0f else inter / union
    }
}
