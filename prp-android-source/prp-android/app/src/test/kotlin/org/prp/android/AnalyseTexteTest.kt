package org.prp.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prp.android.filtre.AnalyseTexte

class AnalyseTexteTest {
    private fun adulte(t: String) = AnalyseTexte.evaluer(t).adulte

    @Test fun pagesDeVideosPornographiques() {
        // titres typiques d'une page de vidéos (anglais, russe, arabe, espagnol, japonais)
        assertTrue(adulte("Free Porn Videos - XXX Movies. Hot MILF blowjob and cumshot. Teen anal gangbang. " +
            "Pornstar threesome HD. Amateur sex video 18+. Home Categories Pornstars Upload"))
        assertTrue(adulte("Порно видео бесплатно. Минет от зрелой мамки. Анальный секс видео. Групповуха, " +
            "кончил в рот. Порнозвезды. Русское домашнее порно"))
        assertTrue(adulte("افلام سكس عربي - نيك مصري - سكس محارم porn xxx hd sex video milf"))
        assertTrue(adulte("Videos porno gratis. Mamada y corrida, sexo anal, orgía casera, maduras follando, pornoactriz xxx"))
        assertTrue(adulte("無料エロ動画 無修正 中出し AV女優 アダルト動画 素人 xxx porn"))
    }

    @Test fun pagesOrdinaires() {
        // article de presse sur la pornographie et la loi
        assertFalse(adulte("La loi sur la vérification de l'âge des sites pornographiques entre en vigueur. " +
            "Les plateformes comme Pornhub devront contrôler l'âge des visiteurs. Les associations de protection " +
            "de l'enfance saluent une avancée contre l'exposition des mineurs à la pornographie."))
        // éducation sexuelle
        assertFalse(adulte("Santé sexuelle des adolescents : parler de sexe, de consentement et de contraception. " +
            "La masturbation est normale. Les infections sexuellement transmissibles se préviennent."))
        // sport, plage, mode
        assertFalse(adulte("Programme musculation : 30 minutes torse nu à la salle. Maillots de bain 2026, " +
            "bikinis et shorts de plage. Leggings de sport pour femmes."))
        assertFalse(adulte("University of Essex and Sussex: courses, admissions, student life, Middlesex campus"))
        // médecine (trachée)
        assertFalse(adulte("Трахея и бронхи: анатомия дыхательных путей, лечение трахеита у детей"))
        // mots ordinaires qui contiennent un mot vulgaire (страх, закончил, банальный, canale, recogiendo)
        assertFalse(adulte("Страхование жизни: страховой полис, страх перед будущим. Он закончил школу, " +
            "банальный вопрос, канальный уровень. Колеблется курс. Голосование и выборы."))
        assertFalse(adulte("Il canale televisivo trasmette gli annali. Estamos recogiendo y escogiendo frutas " +
            "maduras. La corrida de toros. Un pájaro. On abaisse le prix. Coroas de ouro."))
    }

    @Test fun sitesDInformation() {
        assertTrue(AnalyseTexte.estInformation("fr.wikipedia.org"))
        assertTrue(AnalyseTexte.estInformation("www.bbc.co.uk"))
        assertFalse(AnalyseTexte.estInformation("inconnu-xyz.net"))
    }
}
