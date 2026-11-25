package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.filtre.Filtre
import org.prp.android.filtre.ListeSites
import org.prp.android.filtre.Raison
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class ListeSitesTest {
    @Test fun hachageIdentiqueAuScriptPython() {
        // valeurs calculées par outils/liste_sites.py
        assertEquals(-5808556873153909620L, ListeSites.hacher("a"))
        assertEquals(-5518577871501868968L, ListeSites.hacher("pornhub.com"))
    }

    @Test fun lectureDuFormat() {
        val valeurs = listOf("b.com", "a.org", "c.net").map { ListeSites.hacher(it) }.sorted()
        val o = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(o)).use { d -> valeurs.forEach { d.writeLong(it) } }
        val l = ListeSites.charger(ByteArrayInputStream(o.toByteArray()))
        assertEquals(3, l.taille)
        assertTrue("a.org" in l)
        assertFalse("d.com" in l)
    }

    /** La vraie liste embarquée : sites visés bloqués, sites courants laissés. */
    @Test fun vraieListe() {
        val f = listOf("src/main/assets/sites.bin", "app/src/main/assets/sites.bin").map { File(it) }.firstOrNull { it.exists() }
            ?: return
        val l = f.inputStream().use { ListeSites.charger(it) }
        assertTrue(l.taille > 500_000)
        val filtre = Filtre(l)
        assertEquals(Raison.LISTE, filtre.verifierHote("chaturbate.com").raison)
        assertEquals(Raison.LISTE, filtre.verifierHote("fr.chaturbate.com").raison)
        for (h in listOf("reddit.com", "x.com", "vk.com", "ok.ru", "rutube.ru", "telegram.org", "github.com",
            "kinopoisk.ru", "wikipedia.org", "hse.ru", "imgur.com", "instagram.com", "netflix.com", "moviebox.ng"))
            assertFalse(h, filtre.verifierHote(h).bloque)
    }
}
