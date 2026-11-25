package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.detection.Classement
import org.prp.android.detection.Pretraitement
import org.prp.android.detection.Zone

class DetectionTest {
    private fun p(dessin: Float, hentai: Float, neutre: Float, porno: Float, sexy: Float) =
        floatArrayOf(dessin, hentai, neutre, porno, sexy)

    @Test fun pornographieFloutee() {
        assertTrue(Classement.aFlouter(p(0f, 0f, 0.1f, 0.85f, 0.05f), 1, false))
        assertTrue(Classement.aFlouter(p(0.1f, 0.7f, 0.1f, 0.05f, 0.05f), 1, false))   // hentaï
        assertTrue(Classement.aFlouter(p(0f, 0.3f, 0.1f, 0.35f, 0.25f), 1, false))     // les deux ensemble
    }

    @Test fun maillotsEtNeutreLaisses() {
        // « Sexy » (maillot, sous-vêtements, torse nu) : jamais flouté par défaut
        assertFalse(Classement.aFlouter(p(0f, 0f, 0.05f, 0.05f, 0.9f), 1, false))
        assertFalse(Classement.aFlouter(p(0f, 0f, 0.05f, 0.05f, 0.9f), 2, false))
        assertFalse(Classement.aFlouter(p(0.05f, 0f, 0.9f, 0.03f, 0.02f), 0, false))
        assertFalse(Classement.aFlouter(p(0.9f, 0.05f, 0.05f, 0f, 0f), 1, false))       // dessin ordinaire
    }

    @Test fun modeEnfant() {
        assertTrue(Classement.aFlouter(p(0f, 0f, 0.05f, 0.05f, 0.9f), 1, true))
        assertFalse(Classement.aFlouter(p(0f, 0f, 0.9f, 0.05f, 0.05f), 1, true))
    }

    @Test fun sensibilite() {
        val limite = p(0f, 0f, 0.48f, 0.52f, 0f)
        assertTrue(Classement.aFlouter(limite, 0, false))     // stricte
        assertFalse(Classement.aFlouter(limite, 1, false))    // normale
        assertFalse(Classement.aFlouter(limite, 2, false))    // souple
        assertTrue(Classement.seuil(0) < Classement.seuil(1) && Classement.seuil(1) < Classement.seuil(2))
        // stricte : « Sexy » compte à moitié
        assertTrue(Classement.aFlouter(p(0f, 0f, 0.1f, 0.25f, 0.65f), 0, false))
        assertFalse(Classement.aFlouter(p(0f, 0f, 0.1f, 0.25f, 0.65f), 1, false))
    }

    @Test fun resume() {
        assertEquals("Porn 85%", Classement.resume(p(0f, 0f, 0.1f, 0.85f, 0.05f)))
    }

    @Test fun candidatsEtTuiles() {
        val zones = listOf(
            Zone(0f, 0f, 48f, 48f),            // icône : ignorée
            Zone(0f, 100f, 1080f, 700f),       // grande image
            Zone(100f, 800f, 400f, 1100f),     // petite image
            Zone(0f, 0f, 3000f, 5000f),        // plus grande que l'écran : ignorée
        )
        assertEquals(listOf(1, 2), Classement.candidats(zones, 1080f, 2400f))
        val t = Classement.tuiles(1080f, 2400f)
        assertEquals(6, t.size)
        assertEquals(1080f * 2400f, t.sumOf { it.aire.toDouble() }.toFloat(), 1f)
    }

    @Test fun flouUniforme() {
        val l = 6; val h = 4
        val px = IntArray(l * h) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val f = org.prp.android.detection.Pretraitement.flouBoite(px, l, h, 2, 2)
        assertEquals(l * h, f.size)
        for (v in f) {
            assertEquals(0xFF, (v ushr 24) and 0xFF)                   // reste opaque
            val r = (v shr 16) and 0xFF
            assertTrue(r in 60..200)                                    // damier noir/blanc lissé en gris
        }
    }

    @Test fun pretraitement() {
        val chw = Pretraitement.versCHW(intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt()), 2, 1)
        assertEquals(6, chw.size)
        assertEquals(0x10 / 255f, chw[0], 0.0001f)         // plan rouge, pixel 0
        assertEquals(1f, chw[1], 0.0001f)
        assertEquals(0x20 / 255f, chw[2], 0.0001f)         // plan vert
        assertEquals(0x30 / 255f, chw[4], 0.0001f)         // plan bleu
    }

    @Test fun ecranInchangeEtNoir() {
        val a = IntArray(64 * 128) { 0xFF808080.toInt() }
        val b = a.copyOf()
        val sa = Pretraitement.signature(a, 64, 128)
        assertEquals(0f, Pretraitement.difference(sa, Pretraitement.signature(b, 64, 128)), 0.0001f)
        val c = IntArray(64 * 128) { 0xFFFFFFFF.toInt() }
        assertTrue(Pretraitement.difference(sa, Pretraitement.signature(c, 64, 128)) > 0.4f)
        assertEquals(1f, Pretraitement.difference(null, sa), 0f)
        assertTrue(Pretraitement.luminanceMoyenne(IntArray(64 * 128) { 0xFF000000.toInt() }, 64, 128) < 3f)
        assertFalse(Pretraitement.luminanceMoyenne(a, 64, 128) < 3f)
        assertTrue(Pretraitement.couleurProche(0xFF1B2330.toInt(), 27, 35, 48, 12))
        assertFalse(Pretraitement.couleurProche(0xFF808080.toInt(), 27, 35, 48, 12))
    }
}
