package org.prp.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import java.util.Locale

/** Langue et thème choisis dans PrP, et couleurs de l'interface. */
object Apparence {
    /** Langues proposées : code ("" = celle du téléphone) et nom affiché dans sa propre langue. */
    val LANGUES = listOf("fr" to "Français", "en" to "English", "ru" to "Русский")

    /** À appeler dans attachBaseContext : applique la langue et le thème choisis. */
    fun envelopper(base: Context): Context {
        val e = Etat(base)
        val langue = e.langue
        val theme = e.theme
        if (langue.isEmpty() && theme == 0) return base
        val config = Configuration(base.resources.configuration)
        if (langue.isNotEmpty()) config.setLocale(Locale(langue))
        if (theme != 0) {
            val nuit = if (theme == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nuit
        }
        return base.createConfigurationContext(config)
    }

    fun estSombre(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales.get(0) ?: Locale.getDefault()

    class Palette(
        val fond: Int,
        val carte: Int,
        val bordure: Int,
        val texte: Int,
        val texteDoux: Int,
        val primaire: Int,
        val surPrimaire: Int,
        val vert: Int,
        val rouge: Int,
        val orange: Int,
        val fondVert: Int,
        val fondRouge: Int,
        val fondOrange: Int,
        val fondPrimaire: Int,
    )

    private val CLAIRE = Palette(
        fond = Color.rgb(240, 242, 245),
        carte = Color.WHITE,
        bordure = Color.rgb(222, 226, 232),
        texte = Color.rgb(24, 32, 44),
        texteDoux = Color.rgb(90, 98, 110),
        primaire = Color.rgb(31, 58, 95),
        surPrimaire = Color.WHITE,
        vert = Color.rgb(27, 122, 61),
        rouge = Color.rgb(176, 42, 42),
        orange = Color.rgb(176, 104, 0),
        fondVert = Color.rgb(226, 244, 232),
        fondRouge = Color.rgb(251, 229, 229),
        fondOrange = Color.rgb(253, 240, 214),
        fondPrimaire = Color.rgb(224, 232, 244),
    )

    private val SOMBRE = Palette(
        fond = Color.rgb(16, 20, 26),
        carte = Color.rgb(29, 35, 44),
        bordure = Color.rgb(48, 56, 68),
        texte = Color.rgb(232, 236, 242),
        texteDoux = Color.rgb(160, 170, 184),
        primaire = Color.rgb(122, 168, 232),
        surPrimaire = Color.rgb(12, 24, 40),
        vert = Color.rgb(102, 204, 138),
        rouge = Color.rgb(240, 120, 120),
        orange = Color.rgb(240, 180, 90),
        fondVert = Color.rgb(24, 52, 36),
        fondRouge = Color.rgb(64, 28, 30),
        fondOrange = Color.rgb(60, 44, 18),
        fondPrimaire = Color.rgb(30, 46, 70),
    )

    fun palette(ctx: Context): Palette = if (estSombre(ctx)) SOMBRE else CLAIRE

    // ---------------------------------------------------------------- durées

    const val MINUTE = 60L
    const val HEURE = 3600L
    const val JOUR = 86_400L
    const val MOIS = 30 * 86_400L

    /** Unités proposées dans « Autre… » : libellé et nombre de secondes. */
    fun unites(ctx: Context): List<Pair<String, Long>> = listOf(
        ctx.getString(R.string.unite_minutes) to MINUTE,
        ctx.getString(R.string.unite_heures) to HEURE,
        ctx.getString(R.string.unite_jours) to JOUR,
        ctx.getString(R.string.unite_mois) to MOIS,
    )

    /** « 30 jours », « 2 heures »… dans la langue choisie. */
    fun enClair(ctx: Context, secondes: Long): String {
        val r = ctx.resources
        fun p(id: Int, n: Long) = r.getQuantityString(id, n.toInt(), n.toInt())
        return when {
            secondes >= MOIS && secondes % MOIS == 0L -> p(R.plurals.p_mois, secondes / MOIS)
            secondes >= JOUR && secondes % JOUR == 0L -> p(R.plurals.p_jours, secondes / JOUR)
            secondes >= HEURE && secondes % HEURE == 0L -> p(R.plurals.p_heures, secondes / HEURE)
            else -> p(R.plurals.p_minutes, (secondes + 59) / 60)
        }
    }

    /** Forme courte pour les puces : « 5 min », « 7 j »… */
    fun court(ctx: Context, secondes: Long): String = when {
        secondes >= MOIS && secondes % MOIS == 0L -> ctx.getString(R.string.court_mois, (secondes / MOIS).toInt())
        secondes >= JOUR && secondes % JOUR == 0L -> ctx.getString(R.string.court_j, (secondes / JOUR).toInt())
        secondes >= HEURE && secondes % HEURE == 0L -> ctx.getString(R.string.court_h, (secondes / HEURE).toInt())
        else -> ctx.getString(R.string.court_min, ((secondes + 59) / 60).toInt())
    }

    /** Temps restant arrondi, deux unités au plus : « 4 j 3 h », « 2 h 15 min », « 3 min ». */
    fun restant(ctx: Context, secondes: Long): String {
        val s = maxOf(0L, secondes)
        val j = s / JOUR
        val h = (s % JOUR) / HEURE
        val m = (s % HEURE + 59) / 60
        return when {
            j > 0 -> ctx.getString(R.string.court_j, j.toInt()) + " " + ctx.getString(R.string.court_h, h.toInt())
            h > 0 -> ctx.getString(R.string.court_h, h.toInt()) + " " + ctx.getString(R.string.court_min, m.toInt())
            else -> ctx.getString(R.string.court_min, maxOf(1L, m).toInt())
        }
    }
}
