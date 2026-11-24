package org.prp.android

import android.content.Context
import android.content.SharedPreferences

/**
 * État du verrou. Rangé dans le stockage « protégé par l'appareil » pour être lisible
 * dès le démarrage, avant même le déverrouillage de l'écran.
 */
class Etat(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("prp_etat", Context.MODE_PRIVATE)

    var verrouActif: Boolean
        get() = p.getBoolean("verrou", false)
        set(v) { p.edit().putBoolean("verrou", v).commit() }

    /** Ronde drand à partir de laquelle on a le droit de DEMANDER la sortie. */
    var rondeCible: Long
        get() = p.getLong("cible", 0)
        set(v) { p.edit().putLong("cible", v).commit() }

    /** Délai de réflexion, en rondes. */
    var delaiRondes: Long
        get() = p.getLong("delai", 0)
        set(v) { p.edit().putLong("delai", v).commit() }

    /** Ronde à partir de laquelle la sortie demandée devient possible (0 = aucune demande). */
    var rondeLiberation: Long
        get() = p.getLong("liberation", 0)
        set(v) { p.edit().putLong("liberation", v).commit() }

    var dnsDegrade: Boolean
        get() = p.getBoolean("dns_degrade", false)
        set(v) { p.edit().putBoolean("dns_degrade", v).commit() }

    /** Détection d'images à l'écran : éteinte tant qu'on ne l'allume pas ; le verrou l'allume. */
    var detectionActive: Boolean
        get() = p.getBoolean("detection", false)
        set(v) { p.edit().putBoolean("detection", v).commit() }

    /** 0 = stricte, 1 = normale, 2 = souple. */
    var sensibilite: Int
        get() = p.getInt("sensibilite", 1)
        set(v) { p.edit().putInt("sensibilite", v.coerceIn(0, 2)).commit() }

    /** Langue de l'interface : "" = celle du téléphone, sinon "fr", "en" ou "ru". */
    var langue: String
        get() = p.getString("langue", "") ?: ""
        set(v) { p.edit().putString("langue", v).commit() }

    /** Thème : 0 = comme le téléphone, 1 = clair, 2 = sombre. */
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) { p.edit().putInt("theme", v.coerceIn(0, 2)).commit() }

    var echecsReseau: Int
        get() = p.getInt("echecs_reseau", 0)
        set(v) { p.edit().putInt("echecs_reseau", v).commit() }
}
