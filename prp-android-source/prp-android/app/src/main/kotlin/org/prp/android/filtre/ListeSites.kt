package org.prp.android.filtre

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Liste de sites pour adultes, gardée en mémoire sous forme compacte : le hachage FNV-1a 64 bits
 * de chaque domaine, trié (environ 7,5 Mo pour 940 000 domaines au lieu d'une centaine de Mo
 * de chaînes). Une recherche est une recherche dichotomique. Code pur.
 * Fichier produit par outils/liste_sites.py.
 */
class ListeSites(private val hachages: LongArray) {
    val taille: Int get() = hachages.size

    operator fun contains(domaine: String): Boolean =
        hachages.isNotEmpty() && java.util.Arrays.binarySearch(hachages, hacher(domaine)) >= 0

    companion object {
        val VIDE = ListeSites(LongArray(0))

        fun hacher(s: String): Long {
            var h = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325
            for (c in s) {
                h = h xor (c.code.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }

        fun charger(flux: InputStream): ListeSites {
            val entree = DataInputStream(GZIPInputStream(flux).buffered(1 shl 16))
            var t = LongArray(1 shl 20)
            var n = 0
            try {
                while (true) {
                    val v = entree.readLong()
                    if (n == t.size) t = t.copyOf(t.size * 2)
                    t[n++] = v
                }
            } catch (e: EOFException) { }
            val res = t.copyOf(n)
            java.util.Arrays.sort(res)                 // déjà trié ; par sécurité
            return ListeSites(res)
        }

        fun depuis(domaines: Collection<String>): ListeSites =
            ListeSites(domaines.map { hacher(it) }.toLongArray().also { java.util.Arrays.sort(it) })
    }
}
