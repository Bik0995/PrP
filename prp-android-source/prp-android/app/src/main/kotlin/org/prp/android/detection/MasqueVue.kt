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
 * Fenêtre posée par-dessus tout l'écran. Une image visée est recouverte par sa propre version
 * floutée (très réduite puis agrandie), avec une pastille opaque au centre. Une zone sans image
 * reconnue est recouverte d'un aplat opaque. La pastille et l'aplat ont la couleur [COULEUR],
 * que la calibration sait reconnaître dans une capture d'écran.
 */
class MasqueVue(ctx: Context) : View(ctx) {
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COULEUR }
    private val voile = Paint().apply { color = 0x66101820 }
    private val dessin = Paint(Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val origine = IntArray(2)

    var masques: List<Masque> = emptyList()
        set(v) { field = v; invalidate() }

    /** Les aplats (zones sans image reconnue) s'effacent un instant pendant une vérification. */
    var cacheAplats: Boolean = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origine)
        val dx = -origine[0].toFloat()
        val dy = -origine[1].toFloat()
        for (m in masques) {
            if (!m.image && cacheAplats) continue
            r.set(m.rect); r.offset(dx, dy)
            val f = m.flou
            if (m.image && f != null && !f.isRecycled) {
                canvas.drawBitmap(f, null, r, dessin)
                canvas.drawRect(r, voile)
            } else {
                canvas.drawRect(r, plein)
            }
            if (m.image) {
                val p = pastille(m.rect)
                p.offset(dx, dy)
                canvas.drawRoundRect(p, p.width() / 4f, p.width() / 4f, plein)
            }
        }
    }

    companion object {
        /** Couleur opaque (27, 35, 48) : aplats et pastilles. */
        const val COULEUR: Int = 0xFF1B2330.toInt()
        const val R = 27
        const val G = 35
        const val B = 48

        /** Pastille centrale d'un masque d'image, en pixels d'écran. */
        fun pastille(rect: RectF): RectF {
            val c = (minOf(rect.width(), rect.height()) * 0.3f).coerceAtLeast(8f)
            return RectF(rect.centerX() - c / 2f, rect.centerY() - c / 2f, rect.centerX() + c / 2f, rect.centerY() + c / 2f)
        }
    }
}
