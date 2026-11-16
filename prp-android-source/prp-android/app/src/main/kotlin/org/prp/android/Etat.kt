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

    var echecsReseau: Int
        get() = p.getInt("echecs_reseau", 0)
        set(v) { p.edit().putInt("echecs_reseau", v).commit() }
}
