package org.prp.android.filtre

/**
 * Blocage qui ne dépend d'aucune langue : quand la détection floute plusieurs images
 * pornographiques sur un même site en peu de temps, le site entier est ajouté aux sites
 * bloqués. Les grandes plateformes au contenu mixte ne sont jamais apprises : leurs images
 * restent floutées une par une. Code pur.
 */
class Apprentissage(
    private val seuil: Int = 3,
    private val fenetreMs: Long = 120_000L,
) {
    private val vues = HashMap<String, ArrayDeque<Long>>()

    /**
     * [nombre] images floutées sur [hote] à l'instant [t]. Renvoie le nom à bloquer quand le
     * seuil est atteint (une seule fois), sinon null.
     */
    @Synchronized
    fun signaler(hote: String, nombre: Int, t: Long): String? {
        val h = nettoyer(hote) ?: return null
        if (nombre <= 0 || estPlateforme(h)) return null
        val f = vues.getOrPut(h) { ArrayDeque() }
        repeat(nombre.coerceAtMost(seuil)) { f.addLast(t) }
        while (f.isNotEmpty() && t - f.first() > fenetreMs) f.removeFirst()
        if (f.size < seuil) return null
        vues.remove(h)
        return h
    }

    /** Même compte pour une conversation ou un groupe (clé libre, sans nom de site). */
    @Synchronized
    fun signalerCle(cle: String, nombre: Int, t: Long): Boolean {
        if (nombre <= 0) return false
        val f = vues.getOrPut(cle) { ArrayDeque() }
        repeat(nombre.coerceAtMost(seuil)) { f.addLast(t) }
        while (f.isNotEmpty() && t - f.first() > fenetreMs) f.removeFirst()
        if (f.size < seuil) return false
        vues.remove(cle)
        return true
    }

    companion object {
        fun nettoyer(hote: String): String? {
            var h = hote.trim().trimEnd('.').lowercase()
            for (p in listOf("www.", "m.", "mobile.")) if (h.startsWith(p) && h.count { it == '.' } >= 2) h = h.removePrefix(p)
            return if (h.contains('.') && h.length >= 4) h else null
        }

        /** Le domaine et tous ses sous-domaines appartiennent à la plateforme. */
        val PLATEFORMES = setOf(
            "google.com", "google.ru", "bing.com", "yandex.ru", "yandex.com", "ya.ru", "duckduckgo.com",
            "reddit.com", "redd.it", "x.com", "twitter.com", "twimg.com", "t.me", "telegram.org",
            "vk.com", "vk.ru", "userapi.com", "ok.ru", "mail.ru", "instagram.com", "facebook.com",
            "fbcdn.net", "threads.net", "bsky.app", "pinterest.com", "imgur.com", "discord.com",
            "youtube.com", "tiktok.com", "dzen.ru", "rutube.ru", "twitch.tv", "kick.com",
            "snapchat.com", "wikipedia.org", "wikimedia.org", "pixiv.net", "deviantart.com",
            "artstation.com", "flickr.com", "bilibili.com", "weibo.com", "xiaohongshu.com",
            "douyin.com", "qq.com", "baidu.com", "nicovideo.jp", "naver.com", "daum.net",
            "livejournal.com", "pikabu.ru", "9gag.com", "quora.com", "medium.com", "whatsapp.com",
        )

        /** Seul le domaine lui-même : ses sous-domaines sont des blogs de particuliers. */
        val HEBERGEURS = setOf("tumblr.com", "blogspot.com", "wordpress.com", "livejournal.com", "substack.com")

        fun estPlateforme(h: String): Boolean =
            PLATEFORMES.any { h == it || h.endsWith(".$it") } || h in HEBERGEURS
    }
}
