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
     * Formes cherchées en minuscules, en début de mot (voir contientMot).
     */
    val FORTES = listOf(
        f("porno", "porn", "порн", "pornô", "پورن", "포르노", "पोर्न", "πορνο", "פורנו", "ポルノ", "色情", "黄片",
            "سكس", "سکس", "หนังโป๊"),
        f("xxx", "xxx"),
        f("hentai", "hentai", "хентай", "変態", "헨타이"),
        f("fellation", "blowjob", "blow job", "минет", "pompino", "mamada", "boquete", "fellation", "oral sex"),
        f("éjaculation", "cumshot", "creampie", "bukkake", "facial cum", "gozada", "sborra",
            "中出し", "ぶっかけ", "内射", "颜射"),
        f("groupe", "gangbang", "gang bang", "threesome", "групповуха", "orgy", "orgie", "orgía", "suruba"),
        f("gorge", "deepthroat", "deep throat"),
        f("masturbation", "handjob", "masturbat", "мастурб", "дрочит", "дрочка", "punheta", "branlette"),
        f("milf", "milf"),
        f("vedette", "pornstar", "porn star", "порнозвезд", "pornoactriz", "av女優", "av女优"),
        f("non censuré", "uncensored", "無修正", "无码", "без цензуры", "sin censura"),
        f("sexe vidéo", "sex video", "sex videos", "sex tape", "sextape", "free sex", "hot sex", "секс видео",
            "видео секс", "videos de sexo", "vídeos de sexo", "video sesso", "vidéos de sexe", "sexe amateur",
            "sexvideo", "секс-видео", "エロ動画", "アダルト動画", "成人视频", "성인영상", "야동", "सेक्स वीडियो",
            "phim sex", "phim heo", "bokep", "افلام سكس"),
        f("nu explicite", "nude girls", "naked girls", "nude video", "голые девушки", "голая девушка",
            "chicas desnudas", "mulheres peladas", "filles nues"),
        f("anal", "anal sex", "anal video", "anal hd", "anal girl", "anal teen", "sexo anal", "анальн", "anale", "анал "),
        f("vulgaire", "fuck", "fucked", "fucking", "pussy", "трах", "ебл", "ебёт", "ебет", "follando", "cogiendo",
            "transando", "scopata", "salope", "ficken", "fickt", "sikiş", "siktir", "做爱", "섹스",
            "ngentot", "memek", "चुदाई", "เย็ด", "نيك", "شرموط", "منيوك"),
        f("sites", "xvideos", "xnxx", "pornhub", "xhamster", "redtube", "youporn", "spankbang", "chaturbate",
            "stripchat", "bongacams", "onlyfans leak", "brazzers"),
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

    /**
     * Une RECHERCHE (application, barre d'adresse, moteur de recherche, même en navigation privée).
     *
     * - Recherche liée à un mineur et au sexe : toujours refusée.
     * - Mot de contexte (addiction, arrêter, santé, loi, éducation, médecin…) : toujours acceptée.
     *   Les sites, le texte des pages et les images restent filtrés derrière.
     * - Mot sans ambiguïté (porno, pornhub, milf, blowjob, سكس, エロ動画…) : refusée.
     * - Mot à double sens (anal, masturbation, orgie, xxx, uncensored, Deep Throat, juron…) :
     *   refusée seulement avec un autre signe de recherche pornographique (vidéo, gratuit, HD…).
     * - Deux mots moyens dont « sexe » ou « nu » : refusée.
     */
    fun rechercheAdulte(q: String, sociale: Boolean = false): Boolean {
        val t = " " + q.lowercase() + " "
        val r = evaluer(q)
        val sexuel = r.fortes > 0 || "sexe" in r.familles || "nu" in r.familles
        if (MINEURS_ALONE.any { contientMot(t, it) }) return true
        if (sexuel && MINEURS.any { contientMot(t, it) }) return true
        if (CONTEXTE.any { contientMot(t, it) }) return false
        val fortes = r.familles.filter { nom -> FORTES.any { it.nom == nom } }
        val sures = fortes.filter { it !in DOUBLE_SENS }.filter { it != "hentai" || !hentaiAmbigu(t) }
        if (sures.isNotEmpty()) return true
        val signe = INTENTION.any { contientMot(t, it) } || r.moyennes >= 1 || fortes.size >= 2
        if (fortes.isNotEmpty() && signe) return true
        // dans une application sociale, chercher « sexe » ou « sexy » seul ne sert pas à un cours :
        // les résultats sont des profils et des groupes. Un seul mot moyen suffit.
        if (sociale && r.familles.any { it in MOYENNES_SOCIALES }) return true
        return r.moyennes >= 2 && ("sexe" in r.familles || "nu" in r.familles)
    }

    /** Moteurs de recherche et nom du paramètre qui porte la recherche dans leur adresse. */
    private val MOTEURS = listOf(
        "google." to listOf("q"), "bing.com" to listOf("q"), "yandex." to listOf("text"), "ya.ru" to listOf("text"),
        "duckduckgo.com" to listOf("q"), "yahoo.com" to listOf("p", "q"), "ecosia.org" to listOf("q"),
        "qwant.com" to listOf("q"), "baidu.com" to listOf("wd", "word"), "mail.ru" to listOf("q"),
        "search.brave.com" to listOf("q"), "startpage.com" to listOf("query", "q"), "youtube.com" to listOf("search_query"),
        "naver.com" to listOf("query"), "seznam.cz" to listOf("q"), "yahoo.co.jp" to listOf("p"),
    )

    /** La recherche contenue dans l'adresse d'une page de résultats, ou null. */
    fun requeteDansUrl(url: String): String? {
        var u = url.trim()
        if (u.isEmpty() || u.any { it.isWhitespace() } || '?' !in u) return null
        if ("://" !in u) u = "https://$u"
        val hote = Filtre.extraireHote(u) ?: return null
        val noms = MOTEURS.firstOrNull { (m, _) -> if (m.endsWith(".")) hote.contains(m) else hote == m || hote.endsWith(".$m") }
            ?.second ?: return null
        val params = u.substringAfter('?').substringBefore('#').split('&')
        for (p in params) {
            val k = p.substringBefore('=')
            if (k in noms) return try {
                java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
            } catch (e: Exception) { p.substringAfter('=', "") }
        }
        return null
    }

    /** Mots moyens refusés seuls dans les applications sociales (« amateur » reste permis). */
    val MOYENNES_SOCIALES = setOf("sexe", "nu", "seins", "hardcore", "érotique", "excitée", "fétiche", "ados",
        "webcam", "escort", "lesbiennes", "gay", "adulte")

    /** Recherche qui touche au sujet sans être refusée : l'écran suivant est surveillé plus strictement. */
    fun rechercheSensible(q: String): Boolean {
        val r = evaluer(q)
        return r.fortes > 0 || r.moyennes > 0
    }

    /** « 変態 » seul veut aussi dire « pervers » ou « métamorphose » en japonais. */
    private fun hentaiAmbigu(t: String) =
        FORTES.first { it.nom == "hentai" }.formes.none { it != "変態" && contientMot(t, it) }

    /** Familles fortes qui ont aussi un sens ordinaire (médecine, histoire, cinéma, jurons). */
    val DOUBLE_SENS = setOf("anal", "masturbation", "groupe", "gorge", "xxx", "non censuré", "vulgaire")

    /** Signes qu'on cherche des vidéos ou des photos. */
    val INTENTION = listOf(
        "video", "vidéo", "videos", "vidéos", "vídeo", "видео", "фото", "photo", "pics", "gratuit", "free",
        "gratis", "бесплатно", "hd", "онлайн", "online", "en ligne", "girls", "girl", "teen", "teens", "watch",
        "porno", "動画", "视频", "影片", "فيديو", "مقاطع", "동영상", "movie", "movies", "film", "films", "клип",
    )

    /** Recherches d'aide, de soin, de savoir ou d'actualité : jamais bloquées. */
    val CONTEXTE = listOf(
        "addiction", "addict", "dépendance", "dependance", "arrêter", "arreter", "quit", "quitting", "stop",
        "sevrage", "aide", "help", "effet", "effets", "effect", "effects", "loi", "lois", "law", "legal",
        "légal", "santé", "sante", "health", "éducation", "education", "médecin", "medecin", "doctor",
        "médical", "medical", "traitement", "treatment", "thérapie", "therapie", "therapy", "symptôme",
        "symptome", "symptom", "maladie", "disease", "fissure", "fistule", "fistula", "hémorroïde",
        "étude", "study", "research", "recherche scientifique", "histoire", "history", "protéger", "proteger",
        "protect", "parental", "parler", "talk", "scandale", "scandal", "affaire", "cerveau", "brain",
        "psycholog", "sexolog", "consentement", "consent",
        "зависимост", "бросить", "бросать", "отказ", "помощь", "лечени", "врач", "болезн", "здоров",
        "закон", "эффект", "влияни", "защит", "родител", "трещин", "свищ", "психолог", "сексолог",
        "история", "скандал", "исследован", "мозг",
        "adicción", "adiccion", "dejar", "ayuda", "salud", "tratamiento", "ley", "vício", "vicio",
        "parar", "saúde", "ajuda", "sucht", "hilfe", "dipendenza",
        "إدمان", "ادمان", "علاج", "ترك", "依存", "中毒", "戒", "중독",
    )

    /** Mots liés à un mineur : avec un mot sexuel, la recherche est toujours refusée. */
    val MINEURS = listOf(
        "child", "children", "kid", "kids", "enfant", "enfants", "mineur", "mineure", "underage", "minor",
        "ребен", "ребён", "дет", "школьниц", "малолет", "несовершеннолет", "niño", "niña", "menor", "criança",
        "bambin", "kind", "kinder", "طفل", "أطفال", "児童", "儿童", "幼女", "아동",
    )

    /** Mots qui désignent à eux seuls des abus sur mineurs : toujours refusés. */
    val MINEURS_ALONE = listOf("pthc", "preteen", "lolicon", "shotacon", "jailbait", "cp porn")

    fun evaluer(texte: String): Resultat {
        val t = " " + texte.lowercase() + " "
        val trouvees = ArrayList<String>()
        var fortes = 0
        var moyennes = 0
        for (fa in FORTES) if (fa.formes.any { contientMot(t, it) }) { fortes++; trouvees += fa.nom }
        for (fa in MOYENNES) if (fa.formes.any { contientMot(t, it) }) { moyennes++; trouvees += fa.nom }
        return Resultat(fortes, moyennes, trouvees)
    }

    /**
     * On exige un début de mot : « sex » ne doit pas compter dans « Sussex », ni « трах » dans
     * « страх », ni « anale » dans « canale ». Les écritures sans espaces entre les mots (chinois,
     * japonais, coréen, thaï) sont cherchées telles quelles.
     */
    private fun contientMot(t: String, forme: String): Boolean {
        if (forme.any { sansEspaces(it) }) return t.contains(forme)
        var i = t.indexOf(forme)
        while (i >= 0) {
            val avant = if (i == 0) ' ' else t[i - 1]
            if (!avant.isLetterOrDigit()) return true
            i = t.indexOf(forme, i + 1)
        }
        return false
    }

    private fun sansEspaces(c: Char): Boolean {
        val k = c.code
        return k in 0x0E00..0x0E7F || k in 0x3040..0x30FF || k in 0x3400..0x9FFF || k in 0xAC00..0xD7AF
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
