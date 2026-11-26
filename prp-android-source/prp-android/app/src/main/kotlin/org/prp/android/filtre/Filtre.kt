package org.prp.android.filtre

import java.net.IDN

enum class Raison(val libelle: String) {
    MOT_CLE("nom de site explicite"),
    LISTE("liste de sites pour adultes"),
    MOTEUR("moteur de recherche impossible à filtrer"),
    APPRIS("site reconnu à ses images"),
}

data class Verdict(val bloque: Boolean, val hote: String = "", val raison: Raison? = null)

/**
 * Moteur de décision de PrP : dit si un nom de site doit être bloqué.
 * Code pur (aucune dépendance Android) : il se teste sur n'importe quelle machine.
 * Règle de conception : ne bloquer que ce qui est visé. Un mot-clé ne compte que dans
 * le NOM du site, jamais dans le reste de l'adresse ni dans une recherche.
 */
class Filtre(private val liste: ListeSites, appris: Collection<String> = emptyList()) {

    constructor(domaines: Set<String>) : this(ListeSites.depuis(domaines))

    /** Sites bloqués parce que la détection y a vu plusieurs images pornographiques. */
    private val appris: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet(appris))

    fun apprendre(hote: String) { appris.add(hote.lowercase().removePrefix("www.")) }
    fun oublierAppris() { appris.clear() }
    val nbAppris: Int get() = appris.size


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
        var d = normaliserBlogspot(ascii)
        while (true) {
            if (d in liste) return Verdict(true, lisible, Raison.LISTE)
            if (d in appris) return Verdict(true, lisible, Raison.APPRIS)
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

        /**
         * Cherchés n'importe où dans le nom du site (forme lisible et forme ASCII).
         * Seulement des mots sans ambiguïté : un mot courant ailleurs n'a rien à faire ici.
         * La liste de sites couvre le reste ; la détection d'images, ce que la liste ignore.
         */
        val MOTS_CLES = listOf(
            // anglais et international
            "porn", "sex", "xxx", "hentai", "nude", "nsfw", "erotic", "erotik", "erotiq", "erotis",
            "milf", "bdsm", "fuck", "boobs", "camgirl", "rule34", "xvideo", "xnxx", "xhamster",
            "redtube", "spankbang", "brazzers", "chaturbate", "stripchat", "bongacam", "livejasmin",
            "camsoda", "myfreecams", "onlyfans", "fansly", "youjizz", "tnaflix", "blowjob",
            "cumshot", "pornstar", "striptease", "masturbat", "orgasm", "pussy",
            // japonais et chinois (transcriptions)
            "javhd", "javlib", "javbus", "javdb", "javguru", "missav", "supjav",             "oppai", "eromanga", "erogame", "erodouga", "jukujo", "seqing", "caoliu", "yadong",
            // russe (transcriptions)
            "seks", "pornuha", "porevo", "trahun", "trahnul", "shlyuh", "shluhi", "eblya",
            "golye", "golaya", "drochi", "minetik", "pizdat", "prostitutk",
            // arabe, persan, turc (transcriptions)
            "sharmota", "sharmoota", "sharmuta", "aflamsex", "shahvani", "sikis", "sikiş",
            // espagnol, portugais, italien, français, allemand
            "putas", "putitas", "zorras", "follando", "cogiendo", "tetonas", "culonas", "putaria",
            "novinhas", "bucetas", "scopata", "troie", "salope", "chaudasse", "beurette",
            "jacquieetmichel", "ficken", "titten",
            // indonésien, hindi, vietnamien
            "bokep", "ngentot", "memek", "colmek", "chudai", "antarvasna", "phimsex", "phimheo",
            // écritures non latines (noms de domaine internationaux)
            "порн", "секс", "эрот", "хентай", "шлюх", "трах", "проститутк", "ебл", "ебат", "ебут",
            "голые", "минет",
            "سكس", "سکس", "اباحي", "إباحي", "شرموط", "پورن", "شهوانی",
            "色情", "成人视频", "成人影", "黄色网", "黄片", "无码", "做爱", "裸聊", "一级片",
            "エロ", "アダルト", "無修正", "痴漢", "熟女",
            "야동", "섹스", "포르노", "성인방송",
            "सेक्स", "पोर्न", "चुदाई",
            "หนังโป๊", "เย็ด",
            "סקס", "פורנו", "πορνο", "σεξ",
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
            "seksjon", "seksti", "seksten", "sekstant",   // norvégien, danois (« section », 60, 16)
            "disputas", "computas", "imputas", "reputas", // espagnol
            "trahe", "трахе",                            // trachée (latin, russe)
            "сексолог",                                   // sexologue
            "jeuxvideo",                                  // jeuxvideo.com
            "emilf", "amilf",                             // Emil Frey, tamilfont…
            "zerogame", "herogame",
            "bdsmart", "bdsmotos", "bdsmeister",
            "toppaid", "emekli", "atputa", "musikis", "mebly", "salopez", "sekspres", "sekstra",
            "ssexam", "anudet", "anuderm", "xxxlutz", "worxxx", "milfio", "milfey", "milfshake",
            "bdsmovement", "bdsmktg", "cathornerogame",
        )

        /** Sites d'État, d'université, d'école : jamais bloqués sur un mot-clé. */
        private val INSTITUTION = Regex(
            """(^|\.)(gov|edu|mil|int)(\.[a-z]{2})?$|(^|\.)(ac|gob|gouv|go|govt|gv|sch|k12|nic|police|mil|edu)\.[a-z]{2}$"""
        )
        val TLD_ADULTES = listOf(".adult")

        val MOTEURS_NON_FILTRABLES = listOf(
            "qwant.com", "ecosia.org", "startpage.com", "search.brave.com",
            "search.yahoo.com", "go.mail.ru",
        )

        private val BLOGSPOT = Regex("""^(.*\.)?blogspot\.[a-z.]+$""")

        /** x.blogspot.ru, x.blogspot.com.eg… sont des copies de x.blogspot.com. */
        fun normaliserBlogspot(hote: String): String {
            val m = BLOGSPOT.matchEntire(hote) ?: return hote
            return (m.groupValues[1]) + "blogspot.com"
        }

        fun contientMotCle(hote: String): Boolean {
            if (TLD_ADULTES.any { hote.endsWith(it) }) return true
            if (INSTITUTION.containsMatchIn(hote)) return false
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
