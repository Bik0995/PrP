package org.prp.android.filtre

/**
 * Juge une page d'après son TEXTE (titres, légendes, liens), dans toutes les langues.
 *
 * Sert là où PrP ne voit pas l'écran — navigation privée, où le navigateur interdit les
 * captures — et partout ailleurs comme signal supplémentaire. Le navigateur laisse lire le
 * texte même en navigation privée ; rien n'est enregistré ni envoyé.
 *
 * Une page pornographique accumule le vocabulaire explicite (dizaines de titres de vidéos) ;
 * un article qui PARLE de pornographie en contient peu de familles différentes. On compte donc
 * des FAMILLES de mots (porno, porn, pornstar… = une famille), pas des occurrences.
 * Code pur.
 */
object AnalyseTexte {

    /** Une famille de mots : un seul point par famille, quelle que soit la forme trouvée. */
    class Famille(val nom: String, val formes: List<String>, val forte: Boolean)

    private fun f(nom: String, vararg formes: String) = Famille(nom, formes.toList(), true)
    private fun m(nom: String, vararg formes: String) = Famille(nom, formes.toList(), false)

    /**
     * Familles FORTES : vocabulaire qu'on ne trouve presque que sur un site pornographique.
     * Formes cherchées en minuscules, comme sous-chaînes (les langues sans espaces l'exigent).
     */
    val FORTES = listOf(
        f("porno", "porn", "порн", "pornô", "پورن", "포르노", "पोर्न", "πορνο", "פורנו"),
        f("xxx", "xxx"),
        f("hentai", "hentai", "хентай", "変態", "헨타이"),
        f("fellation", "blowjob", "blow job", "минет", "pompino", "mamada", "boquete", "fellation", "oral sex"),
        f("éjaculation", "cumshot", "creampie", "bukkake", "кончил", "кончает", "facial cum", "corrida", "gozada", "sborra"),
        f("groupe", "gangbang", "gang bang", "threesome", "групповуха", "orgy", "orgie", "orgía", "suruba"),
        f("gorge", "deepthroat", "deep throat"),
        f("masturbation", "handjob", "masturbat", "мастурб", "дрочит", "дрочка", "paja", "punheta", "branlette"),
        f("milf", "milf", "мамки", "maduras", "coroas"),
        f("vedette", "pornstar", "porn star", "порнозвезд", "pornoactriz"),
        f("sexe vidéo", "sex video", "sex videos", "sex tape", "sextape", "free sex", "hot sex", "секс видео",
            "видео секс", "videos de sexo", "vídeos de sexo", "video sesso", "vidéos de sexe", "sexe amateur",
            "sexvideo", "секс-видео"),
        f("nu explicite", "nude girls", "naked girls", "nude video", "голые девушки", "голая девушка",
            "chicas desnudas", "mulheres peladas", "filles nues"),
        f("anal", "anal sex", "sexo anal", "анальн", "anale", "анал "),
        f("vulgaire", "fuck", "fucked", "fucking", "pussy", "трах", "ебл", "ебёт", "ебет", "follando", "cogiendo",
            "transando", "scopata", "baise", "salope", "ficken", "fickt", "sikiş", "siktir"),
        f("sites", "xvideos", "xnxx", "pornhub", "xhamster", "redtube", "youporn", "spankbang", "chaturbate",
            "stripchat", "bongacams", "onlyfans leak", "brazzers"),
        f("arabe", "سكس", "سکس", "نيك", "افلام سكس", "شرموط", "منيوك"),
        f("cjk", "エロ動画", "無修正", "アダルト動画", "av女優", "中出し", "成人视频", "色情", "黄片", "无码", "做爱",
            "av女优", "야동", "섹스", "성인영상"),
        f("asie du sud", "चुदाई", "सेक्स वीडियो", "bokep", "ngentot", "memek", "phim sex", "phim heo", "jav uncensored",
            "หนังโป๊", "เย็ด"),
    )

    /** Familles MOYENNES : courantes sur ces sites, mais aussi ailleurs. Elles ne suffisent pas seules. */
    val MOYENNES = listOf(
        m("sexe", "sex", "секс", "sexo", "sesso", "sexe", "seks", "جنسي", "セックス"),
        m("nu", "nude", "naked", "голые", "голая", "desnud", "pelad", "nackt", "nue ", "nues "),
        m("seins", "tits", "boobs", "сиськи", "tetas", "peitos", "titten", "nichons"),
        m("hardcore", "hardcore", "жёстк", "жестк"),
        m("érotique", "erotic", "эротик", "erótic", "erotik", "érotique", "エロ"),
        m("excitée", "horny", "возбужд", "cachonda", "tesuda", "excitée", "geil"),
        m("fétiche", "fetish", "bdsm", "фетиш", "бдсм"),
        m("ados", "teen sex", "teens", "18+", "18 ans", "18 лет", "jovencitas", "novinha"),
        m("amateur", "amateur", "любительск", "casero", "caseiro"),
        m("webcam", "webcam", "cam girl", "camgirl", "live cams", "вебкам"),
        m("escort", "escort", "проститутк", "шлюх", "putas", "putinha"),
        m("lesbiennes", "lesbian", "лесби", "lesbianas", "lésbicas", "lesbiennes"),
        m("gay", "gay sex", "гей секс", "shemale", "трансы"),
        m("adulte", "adult videos", "для взрослых", "para adultos", "pour adultes", "成人", "성인"),
    )

    class Resultat(val fortes: Int, val moyennes: Int, val familles: List<String>) {
        /**
         * Page pornographique : au moins 5 familles fortes, ou 4 fortes et 4 moyennes. Une page
         * de vidéos pornographiques en réunit bien plus (chaque titre en apporte) ; un article
         * de presse ou d'éducation sexuelle sur le sujet n'en réunit pas autant.
         */
        val adulte: Boolean get() = fortes >= 5 || (fortes >= 4 && moyennes >= 4)
    }

    fun evaluer(texte: String): Resultat {
        val t = " " + texte.lowercase() + " "
        val trouvees = ArrayList<String>()
        var fortes = 0
        var moyennes = 0
        for (fa in FORTES) if (fa.formes.any { t.contains(it) }) { fortes++; trouvees += fa.nom }
        for (fa in MOYENNES) if (fa.formes.any { contientMot(t, it) }) { moyennes++; trouvees += fa.nom }
        return Resultat(fortes, moyennes, trouvees)
    }

    /**
     * Pour les formes latines courtes (sex, gay, nude…), on exige un début de mot : « sex » ne
     * doit pas compter dans « Sussex » ou « Essex ». Les écritures sans espaces sont cherchées telles quelles.
     */
    private fun contientMot(t: String, forme: String): Boolean {
        if (forme.any { it.code > 0x24F }) return t.contains(forme)
        var i = t.indexOf(forme)
        while (i >= 0) {
            val avant = if (i == 0) ' ' else t[i - 1]
            if (!avant.isLetterOrDigit()) return true
            i = t.indexOf(forme, i + 1)
        }
        return false
    }

    /** Sites d'information générale et d'encyclopédie : jamais bloqués sur leur texte. */
    val INFORMATION = setOf(
        "wikipedia.org", "wikimedia.org", "wiktionary.org", "britannica.com", "larousse.fr", "universalis.fr",
        "who.int", "nih.gov", "ncbi.nlm.nih.gov", "medlineplus.gov", "doctissimo.fr", "ameli.fr", "mayoclinic.org",
        "webmd.com", "healthline.com", "lemonde.fr", "bbc.com", "bbc.co.uk", "theguardian.com", "nytimes.com",
        "ria.ru", "rbc.ru", "lenta.ru", "meduza.io", "kommersant.ru", "francetvinfo.fr", "lefigaro.fr",
        "google.com", "google.ru", "yandex.ru", "bing.com", "duckduckgo.com",
    )

    fun estInformation(hote: String): Boolean = INFORMATION.any { hote == it || hote.endsWith(".$it") }
}
