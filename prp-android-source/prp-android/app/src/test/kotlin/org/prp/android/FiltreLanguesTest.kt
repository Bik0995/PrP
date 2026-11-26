package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.filtre.Apprentissage
import org.prp.android.filtre.Filtre
import org.prp.android.filtre.Raison

class FiltreLanguesTest {
    private val f = Filtre(emptySet())
    private fun bloque(h: String) = f.verifierHote(h).bloque

    @Test fun motsClesDePlusieursLangues() {
        for (h in listOf(
            "porno365.golf", "pornuha.tv", "seksvideo.tv", "russkoeporevo24.pics", "prostitutki-today.com",
            "порно-видео.рф", "секс-знакомства.рф", "xn----jtbhede0biaeco.cc",     // cyrillique (punycode)
            "aflamsexnyk.top", "sharmota.net", "sikisvakti.online", "shahvani.com",
            "سكس-عربي.com",                                                         // arabe
            "bokepindo24.com", "ngentotkuy.com", "chudaikahani.in", "phimsexhay.com", "phimheo.video",
            "yadongtv107.com", "야동.com", "javhdz.one", "missav123.nl", "eromanga-time.com", "エロ動画.net",
            "色情网.com", "putaria.com.br", "zorrasvip.com", "salope-du-coin.fr", "ficken-live.de",
        )) assertTrue(h, bloque(h))
    }

    @Test fun nomsInnocents() {
        for (h in listOf(
            "jeuxvideo.com", "www.emilfrey.ch", "tamilfontconverter.co.in", "www.xxxlutz.hu", "assessor.gov",
            "smartassessor.co.uk", "sivasekspres.com", "meblyar.ua", "empresalopez.com.ar", "katilimemeklilik.com.tr",
            "atputasvakars.lv", "musikis-saxli.ge", "bdsmovement.net", "mssexam.in", "sexoffenders.gov.tt",
            "caseportal.siskiyou.courts.ca.gov", "vecchiasignora.com", "watchanimeworld.one", "uneekclothing.com",
            "hardcorecatholics.com", "essex.ac.uk", "seksjon.no", "trahere.org", "сексолог-москва.рф", "zerogameth.com",
        )) assertFalse(h, bloque(h))
    }

    @Test fun copiesBlogspot() {
        val l = Filtre(setOf("hotbabes.blogspot.com"))
        assertTrue(l.verifierHote("hotbabes.blogspot.com.eg").bloque)
        assertTrue(l.verifierHote("hotbabes.blogspot.ru").bloque)
        assertFalse(l.verifierHote("cuisine.blogspot.ru").bloque)
    }

    @Test fun apprentissage() {
        val a = Apprentissage(seuil = 3, fenetreMs = 120_000L)
        assertNull(a.signaler("www.inconnu-xyz.net", 1, 0L))
        assertNull(a.signaler("inconnu-xyz.net", 1, 10_000L))
        assertEquals("inconnu-xyz.net", a.signaler("inconnu-xyz.net", 1, 20_000L))
        // trop espacé dans le temps : rien
        assertNull(a.signaler("autre.org", 2, 0L))
        assertNull(a.signaler("autre.org", 1, 300_000L))
        // plateformes mixtes : jamais apprises
        assertNull(a.signaler("www.reddit.com", 5, 0L))
        assertNull(a.signaler("pbs.twimg.com", 5, 0L))
        assertNull(a.signaler("tumblr.com", 5, 0L))
        assertEquals("quelqu-un.tumblr.com", a.signaler("quelqu-un.tumblr.com", 3, 0L))

        val filtre = Filtre(emptySet())
        assertFalse(filtre.verifierHote("inconnu-xyz.net").bloque)
        filtre.apprendre("inconnu-xyz.net")
        assertEquals(Raison.APPRIS, filtre.verifierHote("videos.inconnu-xyz.net").raison)
        filtre.oublierAppris()
        assertFalse(filtre.verifierHote("inconnu-xyz.net").bloque)
    }
}
