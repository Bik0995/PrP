package org.prp.android.detection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/** Fenêtre de masque posée par-dessus tout l'écran : dessine des rectangles opaques. */
class MasqueVue(ctx: Context) : View(ctx) {
    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COULEUR }
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 225)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics,
        )
    }
    private val rect = RectF()

    var zones: List<Zone> = emptyList()
        set(v) { field = v; invalidate() }

    /** Pendant une analyse, le masque s'efface une fraction de seconde pour que la capture voie l'écran réel. */
    var cache: Boolean = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (cache) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (z in zones) {
            rect.set(z.g * w, z.h * h, z.d * w, z.b * h)
            canvas.drawRoundRect(rect, 28f, 28f, peinture)
            if (rect.width() > w * 0.35f && rect.height() > h * 0.08f) {
                canvas.drawText("Masqué par PrP", rect.centerX(), rect.centerY(), texte)
            }
        }
    }

    companion object {
        /** Couleur opaque du masque ; la calibration la reconnaît dans une capture (27, 35, 48). */
        const val COULEUR: Int = 0xFF1B2330.toInt()
        const val R = 27
        const val G = 35
        const val B = 48
    }
}
