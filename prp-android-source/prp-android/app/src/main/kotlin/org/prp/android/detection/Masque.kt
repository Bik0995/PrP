package org.prp.android.detection

import kotlin.math.max
import kotlin.math.min

/** Rectangle en pixels d'écran : gauche, haut, droite, bas. */
data class Zone(val g: Float, val h: Float, val d: Float, val b: Float) {
    val l: Float get() = d - g
    val ht: Float get() = b - h
    val aire: Float get() = (d - g) * (b - h)
    fun intersecte(o: Zone): Boolean = g < o.d && o.g < d && h < o.b && o.h < b
    fun union(o: Zone): Zone = Zone(min(g, o.g), min(h, o.h), max(d, o.d), max(b, o.b))
    fun contientCentre(o: Zone): Boolean {
        val cx = (o.g + o.d) / 2f
        val cy = (o.h + o.b) / 2f
        return cx >= g && cx <= d && cy >= h && cy <= b
    }
}
