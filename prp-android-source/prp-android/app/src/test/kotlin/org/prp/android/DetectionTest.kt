package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.detection.Boite
import org.prp.android.detection.Classes
import org.prp.android.detection.ControleurMasque
import org.prp.android.detection.Pretraitement
import org.prp.android.detection.Yolo

class DetectionTest {

    @Test fun tailleDEntree() {
        assertEquals(Pair(320, 704), Yolo.dimensionsEntree(1080, 2400))
        assertEquals(Pair(704, 320), Yolo.dimensionsEntree(2400, 1080))
        assertEquals(Pair(320, 320), Yolo.dimensionsEntree(1000, 1000))
        assertEquals(Pair(320, 800), Yolo.dimensionsEntree(1080, 5000))   // plafonné
        val (l, h) = Yolo.dimensionsEntree(720, 1600)
        assertTrue(l % 32 == 0 && h % 32 == 0)
    }

    /** Sortie fabriquée à la main : 4 ancres, rangement par canal comme le modèle. */
    private fun sortie(ancres: Int, remplir: (FloatArray) -> Unit): FloatArray {
        val s = FloatArray(22 * ancres)
        remplir(s)
        return s
    }

    @Test fun decodageEtDoublons() {
        val n = 4
        val s = sortie(n) { t ->
            fun poser(i: Int, cx: Float, cy: Float, w: Float, h: Float, classe: Int, score: Float) {
                t[i] = cx; t[n + i] = cy; t[2 * n + i] = w; t[3 * n + i] = h
                t[(4 + classe) * n + i] = score
            }
            poser(0, 160f, 352f, 64f, 96f, 3, 0.90f)     // poitrine exposée, forte
            poser(1, 162f, 354f, 64f, 96f, 3, 0.50f)     // doublon presque identique : supprimé
            poser(2, 40f, 600f, 40f, 40f, 4, 0.70f)       // autre endroit : gardé
            poser(3, 100f, 100f, 50f, 50f, 1, 0.10f)      // sous le seuil : ignoré
        }
        val r = Yolo.decoder(s, n, 320, 704, 1080f, 2400f)
        assertEquals(2, r.size)
        val a = r[0]
        assertEquals(3, a.classe)
        assertEquals(432f, a.x, 0.5f)                      // (160 - 32) * 3.375
        assertEquals(1036.4f, a.y, 1f)                     // (352 - 48) * 3.4091
        assertEquals(216f, a.l, 0.5f)
        assertEquals(327.3f, a.h, 1f)
        assertEquals(4, r[1].classe)
    }

    @Test fun torseDHommePasPrisPourUnePoitrine() {
        val n = 2
        val s = sortie(n) { t ->
            fun poser(i: Int, cx: Float, cy: Float) { t[i] = cx; t[n + i] = cy; t[2 * n + i] = 60f; t[3 * n + i] = 60f }
            poser(0, 100f, 200f); poser(1, 250f, 500f)
            t[(4 + 3) * n + 0] = 0.70f; t[(4 + 5) * n + 0] = 0.50f   // hésite avec « torse d'homme » : homme
            t[(4 + 3) * n + 1] = 0.70f; t[(4 + 5) * n + 1] = 0.10f   // nette : reste une poitrine de femme
        }
        val r = Yolo.decoder(s, n, 320, 704, 320f, 704f)
        assertEquals(2, r.size)
        assertEquals(5, r.first { it.x < 150f }.classe)
        assertEquals(3, r.first { it.x > 150f }.classe)
        assertFalse(Classes.visee(5))
    }

    @Test fun bordsDeLImage() {
        val n = 1
        val s = sortie(n) { t -> t[0] = 5f; t[1] = 5f; t[2] = 40f; t[3] = 40f; t[(4 + 14) * n] = 0.8f }
        val b = Yolo.decoder(s, n, 320, 704, 1080f, 2400f)[0]
        assertTrue(b.x >= 0f && b.y >= 0f)
        assertTrue(b.droite <= 1080f && b.bas <= 2400f)
    }

    @Test fun classesVisees() {
        for (c in listOf(2, 3, 4, 6, 14)) assertTrue(Classes.NOMS[c], Classes.visee(c))
        // visages, ventre, pieds, aisselles, partie couverte et torse masculin ne masquent jamais rien
        for (c in listOf(0, 1, 5, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17)) assertFalse(Classes.NOMS[c], Classes.visee(c))
        assertEquals(18, Classes.NOMS.size)
        assertTrue(Classes.seuil(4, 0) < Classes.seuil(4, 1))
        assertTrue(Classes.seuil(4, 1) < Classes.seuil(4, 2))
    }

    private fun boite(x: Float, y: Float, l: Float, h: Float, classe: Int, score: Float) =
        Boite(x, y, l, h, classe, score)

    @Test fun masqueEtMaintien() {
        val c = ControleurMasque(tenueMs = 1000)
        // classe non visée (visage) : rien
        assertEquals(0, c.mettreAJour(listOf(boite(100f, 100f, 100f, 100f, 1, 0.99f)), 1000f, 2000f, 1, 0L))
        assertFalse(c.aDesZones(0L))
        // score trop faible pour la poitrine : rien
        assertEquals(0, c.mettreAJour(listOf(boite(100f, 100f, 100f, 100f, 3, 0.35f)), 1000f, 2000f, 1, 0L))
        // détection nette : une zone, agrandie autour de la boîte
        assertEquals(1, c.mettreAJour(listOf(boite(400f, 800f, 200f, 200f, 3, 0.8f)), 1000f, 2000f, 1, 0L))
        val z = c.actives(500L).single()
        assertTrue(z.g < 0.4f && z.d > 0.6f && z.h < 0.4f && z.b > 0.5f)
        // maintenue 1 s, puis disparue
        assertTrue(c.aDesZones(999L))
        assertFalse(c.aDesZones(1000L))
    }

    @Test fun sensibilite() {
        val b = listOf(boite(400f, 800f, 200f, 200f, 3, 0.50f))   // seuil normal 0,55 ; strict 0,45
        assertEquals(1, ControleurMasque().mettreAJour(b, 1000f, 2000f, 0, 0L))   // stricte : masque
        assertEquals(0, ControleurMasque().mettreAJour(b, 1000f, 2000f, 1, 0L))   // normale : non
        assertEquals(0, ControleurMasque().mettreAJour(b, 1000f, 2000f, 2, 0L))
    }

    @Test fun fusionEtPleinEcran() {
        val c = ControleurMasque(tenueMs = 1000)
        // deux zones qui se touchent : fusionnées en une seule
        c.mettreAJour(listOf(boite(100f, 100f, 100f, 100f, 4, 0.9f), boite(180f, 120f, 100f, 100f, 14, 0.9f)), 1000f, 2000f, 1, 0L)
        assertEquals(1, c.actives(10L).size)
        assertTrue(c.actives(10L).single().aire < 0.30f)
        // trois zones séparées : tout l'écran
        val d = ControleurMasque(tenueMs = 1000, aireMax = 0.30f, nbMax = 3)
        d.mettreAJour(listOf(
            boite(10f, 10f, 60f, 60f, 4, 0.9f), boite(500f, 900f, 60f, 60f, 4, 0.9f), boite(800f, 1700f, 60f, 60f, 4, 0.9f),
        ), 1000f, 2000f, 1, 0L)
        assertEquals(1, d.actives(10L).size)
        assertEquals(1f, d.actives(10L).single().aire, 0.001f)
        // une zone qui occupe plus de 30 % de l'écran : tout l'écran aussi
        val e = ControleurMasque(tenueMs = 1000, aireMax = 0.30f, nbMax = 3)
        e.mettreAJour(listOf(boite(100f, 200f, 700f, 1000f, 3, 0.9f)), 1000f, 2000f, 1, 0L)
        assertEquals(1f, e.actives(10L).single().aire, 0.001f)
    }

    @Test fun jamaisPleinEcranParDefaut() {
        val c = ControleurMasque(tenueMs = 1000)
        c.mettreAJour(listOf(
            boite(10f, 10f, 60f, 60f, 4, 0.9f), boite(500f, 900f, 60f, 60f, 4, 0.9f), boite(800f, 1700f, 60f, 60f, 4, 0.9f),
        ), 1000f, 2000f, 1, 0L)
        assertEquals(3, c.actives(10L).size)
        assertTrue(c.actives(10L).all { it.aire < 0.05f })
    }

    @Test fun choixDeLImageEntiere() {
        val ecran = 1000f * 2000f
        val boite = org.prp.android.detection.Zone(400f, 600f, 500f, 700f)
        val cadres = listOf(
            org.prp.android.detection.Zone(0f, 0f, 1000f, 2000f),      // page entière : refusée
            org.prp.android.detection.Zone(100f, 300f, 900f, 1100f),   // image qui contient la détection
            org.prp.android.detection.Zone(420f, 620f, 480f, 680f),    // trop petite (moins de la moitié)
            org.prp.android.detection.Zone(0f, 1200f, 1000f, 1800f),   // ailleurs
        )
        assertEquals(1, org.prp.android.detection.Cadres.choisir(boite, cadres, ecran))
        // plusieurs images imbriquées : la plus petite qui convient
        val imbriques = cadres + org.prp.android.detection.Zone(300f, 500f, 700f, 900f)
        assertEquals(4, org.prp.android.detection.Cadres.choisir(boite, imbriques, ecran))
        // aucune image autour : -1 (on masquera la zone seule)
        assertEquals(-1, org.prp.android.detection.Cadres.choisir(boite, listOf(cadres[0], cadres[3]), ecran))
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
