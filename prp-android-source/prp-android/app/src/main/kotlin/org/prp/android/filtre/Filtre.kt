package org.prp.android.filtre

import java.net.IDN

enum class Raison(val libelle: String) {
    MOT_CLE("nom de site explicite"),
    LISTE("liste de sites pour adultes"),
    MOTEUR("moteur de recherche impossible à filtrer"),
}

data class Verdict(val bloque: Boolean, val hote: String = "", val raison: Raison? = null)

/**
 * Moteur de décision de PrP : dit si un nom de site doit être bloqué.
 * Code pur (aucune dépendance Android) : il se teste sur n'importe quelle machine.
 * Règle de conception : ne bloquer que ce qui est visé. Un mot-clé ne compte que dans
 * le NOM du site, jamais dans le reste de l'adresse ni dans une recherche.
 */
class Filtre(private val liste: ListeSites) {

    constructor(domaines: Set<String>) : this(ListeSites.depuis(domaines))


    fun verifierUrl(texte: String): Verdict {
        val hote = extraireHote(texte) ?: return LAISSER
        return verifierHote(hote)
    }

    fun verifierHote(brut: String): Verdict {
        val h = brut.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return LAISSER
        val ascii = try { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).lowercase() } catch (e: Exception) { h }
        val lisible = try { IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED).lowercase() } catch (e: Exception) { h }

        if (contientMotCle(lisible) || contientMotCle(ascii)) return Verdict(true, lisible, Raison.MOT_CLE)

        // le site lui-même, puis chacun de ses domaines parents (a.b.c.com, b.c.com, c.com)
        var d = ascii
        while (true) {
            if (d in liste) return Verdict(true, lisible, Raison.LISTE)
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
            if (!d.contains('.')) break
        }

        if (MOTEURS_NON_FILTRABLES.any { ascii == it || ascii.endsWith(".$it") })
            return Verdict(true, lisible, Raison.MOTEUR)
        return LAISSER
    }

    companion object {
        val LAISSER = Verdict(false)

        /** Cherchés n'importe où dans le nom du site. */
        val MOTS_CLES = listOf(
            "porn", "sex", "xxx", "hentai", "nude", "nsfw", "erotic", "erotik", "erotiq",
            "milf", "bdsm", "fuck", "boobs", "camgirl", "rule34",
            "порно", "секс", "эротик", "хентай",
        )

        /** Noms innocents qui contiennent un mot-clé : retirés avant la recherche. */
        val EXCEPTIONS = listOf(
            "essex", "sussex", "middlesex", "wessex", "unisex",
            "sextant", "sexton", "sextet", "sextuple",
            "asexual", "bisexual", "homosexual", "heterosexual",
            "sexualhealth", "sexeducation",
            "pornichet", "pornic",          // villes françaises
            "nudel", "denude",              // « Nudeln » (pâtes, en allemand)
            "milford",
        )

        val TLD_ADULTES = listOf(".adult")

        val MOTEURS_NON_FILTRABLES = listOf(
            "qwant.com", "ecosia.org", "startpage.com", "search.brave.com",
            "search.yahoo.com", "go.mail.ru",
        )

        fun contientMotCle(hote: String): Boolean {
            if (TLD_ADULTES.any { hote.endsWith(it) }) return true
            var t = hote
            for (e in EXCEPTIONS) t = t.replace(e, "|")
            return MOTS_CLES.any { t.contains(it) }
        }

        /**
         * Extrait le nom du site depuis le texte d'une barre d'adresse.
         * Renvoie null si ce n'est pas une adresse web (recherche en cours de frappe,
         * page interne du navigateur, adresse IP…).
         */
        fun extraireHote(texte: String): String? {
            var t = texte.filter { Character.getType(it) != Character.FORMAT.toInt() }.trim().lowercase()
            if (t.isEmpty() || t.length > 4096 || t.any { it.isWhitespace() }) return null
            val s = t.indexOf("://")
            if (s in 1..12) {
                val schema = t.substring(0, s)
                if (schema != "http" && schema != "https") return null
                t = t.substring(s + 3)
            }
            t = t.takeWhile { it != '/' && it != '?' && it != '#' }
            t = t.substringAfterLast('@')
            if (t.startsWith("[")) return null                       // IPv6
            t = t.substringBefore(':').trimEnd('.')
            if (t.length !in 4..253 || !t.contains('.')) return null
            if (t.all { it.isDigit() || it == '.' }) return null     // IPv4
            if (!t.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) return null
            if (t.startsWith(".") || t.contains("..")) return null
            return t
        }

    }
}
