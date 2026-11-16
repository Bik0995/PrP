package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.filtre.Filtre
import org.prp.android.filtre.Raison

class FiltreTest {
    private val f = Filtre(setOf("exemple-adulte.com", "i.4cdn.org"))

    @Test fun motsClesBloques() {
        for (h in listOf("pornhub.com", "www.xvideos-porn.net", "free-sex.tv", "site.xxx", "hentai.example",
            "порно.рф", java.net.IDN.toASCII("порно.рф"), "секс-видео.com", "quelquechose.adult", "nudevista.com"))
            assertTrue(h, f.verifierHote(h).bloque)
    }

    @Test fun sitesInnocentsLaisses() {
        for (h in listOf("essex.ac.uk", "sussex.ac.uk", "middlesex.edu", "unisex-mode.fr", "pornic.fr",
            "nudeln.de", "milford.ct.us", "wikipedia.org", "hse.ru", "git.miem.hse.ru", "youtube.com",
            "google.com", "bing.com", "yandex.ru", "duckduckgo.com", "analytics.google.com",
            "documents.example.com", "asexuality.org", "moviebox.ng", "t.me"))
            assertFalse(h, f.verifierHote(h).bloque)
    }

    @Test fun listeEtSousDomaines() {
        assertEquals(Raison.LISTE, f.verifierHote("exemple-adulte.com").raison)
        assertEquals(Raison.LISTE, f.verifierHote("cdn.video.exemple-adulte.com").raison)
        assertEquals(Raison.LISTE, f.verifierHote("i.4cdn.org").raison)
        assertFalse(f.verifierHote("boards.4cdn.org").bloque)
        assertFalse(f.verifierHote("pas-exemple-adulte.com.fr").bloque)
    }

    @Test fun moteursNonFiltrables() {
        assertEquals(Raison.MOTEUR, f.verifierHote("www.qwant.com").raison)
        assertEquals(Raison.MOTEUR, f.verifierHote("search.brave.com").raison)
        assertFalse(f.verifierHote("brave.com").bloque)
        assertFalse(f.verifierHote("mail.yahoo.com").bloque)
    }

    @Test fun barreDAdresse() {
        assertEquals("pornhub.com", Filtre.extraireHote("pornhub.com/view_video.php?viewkey=1"))
        assertEquals("www.example.com", Filtre.extraireHote("https://www.Example.com:8443/a?b#c"))
        assertEquals("example.com", Filtre.extraireHote("‎https://user@example.com/"))
        assertNull(Filtre.extraireHote("sex education wikipedia"))   // recherche en cours de frappe
        assertNull(Filtre.extraireHote("Rechercher ou saisir une URL"))
        assertNull(Filtre.extraireHote("chrome://flags"))
        assertNull(Filtre.extraireHote("about:blank"))
        assertNull(Filtre.extraireHote("192.168.1.1/admin"))
        assertNull(Filtre.extraireHote("localhost"))
        // le mot-clé dans le chemin ou la recherche ne bloque pas un site ordinaire
        assertFalse(f.verifierUrl("https://fr.wikipedia.org/wiki/Sexe").bloque)
        assertFalse(f.verifierUrl("google.com/search?q=sussex+university").bloque)
        assertTrue(f.verifierUrl("https://m.pornhub.com/").bloque)
    }

    @Test fun horlogeDrand() {
        assertEquals(1234567L, Drand.extraireRonde("""{"round":1234567,"randomness":"ab","signature":"cd"}"""))
        assertNull(Drand.extraireRonde("""{"error":"x"}"""))
        assertEquals(20L, Drand.rondes(60))
        assertEquals(1L, Drand.rondes(1))
        assertEquals(Drand.GENESE_S * 1000, Drand.dateRondeMs(1))
    }

    @Test fun leVerrouNeRaccourcitJamais() {
        assertEquals(1020L, Verrou.nouvelleCible(1000, 0, 60))
        assertEquals(5000L, Verrou.nouvelleCible(1000, 5000, 60))      // prolongation plus courte : ignorée
        assertEquals(1000L + 28_800, Verrou.nouvelleCible(1000, 5000, 86_400))
    }
}
