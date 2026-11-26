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

    /** Mode enfant : floute aussi les torses nus et les maillots ou sous-vêtements. */
    var modeEnfant: Boolean
        get() = p.getBoolean("mode_enfant", false)
        set(v) { p.edit().putBoolean("mode_enfant", v).commit() }

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

    /** Mode choisi dans l'assistant : 0 = pas encore choisi, 1 = Fort, 2 = Simple. */
    var mode: Int
        get() = p.getInt("mode", 0)
        set(v) { p.edit().putInt("mode", v.coerceIn(0, 2)).commit() }

    /** Applications retirées un instant pendant l'installation du Mode Fort, à remettre. */
    var paquetsRetires: List<String>
        get() = (p.getString("paquets_retires", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) { p.edit().putString("paquets_retires", v.joinToString(",")).commit() }

    /** Sites bloqués d'après leurs images (voir filtre/Apprentissage.kt). */
    var sitesAppris: Set<String>
        get() = p.getStringSet("sites_appris", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("sites_appris", HashSet(v)).commit() }

    /** Groupes et conversations bloqués (« paquet|nom »), d'après leurs images. */
    var groupesAppris: Set<String>
        get() = p.getStringSet("groupes_appris", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("groupes_appris", HashSet(v)).commit() }

    var echecsReseau: Int
        get() = p.getInt("echecs_reseau", 0)
        set(v) { p.edit().putInt("echecs_reseau", v).commit() }

    companion object {
        const val MODE_AUCUN = 0
        const val MODE_FORT = 1
        const val MODE_SIMPLE = 2
    }
}
