package org.prp.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Un masque à dessiner : rectangle en pixels d'écran, image floutée facultative, et sa nature. */
class Masque(val rect: RectF, val flou: Bitmap?, val image: Boolean)

/**
 * Fenêtre posée par-dessus tout l'écran. Chaque image visée est recouverte par sa propre
 * version floutée, et rien d'autre (pas de carré ni de voile). Sans image floutée disponible,
 * un aplat opaque de couleur [COULEUR] sert de secours.
 */
class MasqueVue(ctx: Context) : View(ctx) {
    private val plein = Paint().apply { color = COULEUR }
    private val dessin = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val origine = IntArray(2)

    var masques: List<Masque> = emptyList()
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origine)
        val dx = -origine[0].toFloat()
        val dy = -origine[1].toFloat()
        for (m in masques) {
            r.set(m.rect); r.offset(dx, dy)
            val f = m.flou
            if (f != null && !f.isRecycled) canvas.drawBitmap(f, null, r, dessin)
            else canvas.drawRect(r, plein)
        }
    }

    companion object {
        /** Couleur de secours (27, 35, 48). */
        const val COULEUR: Int = 0xFF1B2330.toInt()
    }
}
