package org.prp.android

import android.content.Context

/**
 * Le verrou à sens unique. Chaque fonction renvoie null si tout s'est bien passé,
 * sinon un message d'erreur à montrer. Toutes interrogent drand : à appeler hors du fil principal.
 */
object Verrou {
    const val DUREE_MIN_S = 60L

    /** Règle centrale : une échéance ne peut que reculer, jamais avancer. */
    fun nouvelleCible(rondeActuelle: Long, cibleExistante: Long, dureeS: Long): Long =
        maxOf(cibleExistante, rondeActuelle + Drand.rondes(dureeS))

    @Synchronized
    fun verrouiller(ctx: Context, dureeS: Long, delaiS: Long): String? {
        val e = Etat(ctx)
        if (e.verrouActif) return ctx.getString(R.string.err_deja)
        if (!Protections.estProprietaire(ctx)) return ctx.getString(R.string.err_pas_proprio)
        if (dureeS < DUREE_MIN_S || delaiS < DUREE_MIN_S) return ctx.getString(R.string.err_min)
        val r = Drand.rondeActuelle() ?: return ctx.getString(R.string.err_reseau)
        e.rondeCible = nouvelleCible(r, 0, dureeS)
        e.delaiRondes = Drand.rondes(delaiS)
        e.rondeLiberation = 0
        e.detectionActive = true
        e.verrouActif = true
        Gardien.cycle(ctx, avecDns = true)
        Gardien.programmer(ctx)
        return null
    }

    @Synchronized
    fun prolonger(ctx: Context, dureeS: Long): String? {
        val e = Etat(ctx)
        if (!e.verrouActif) return ctx.getString(R.string.err_aucun)
        if (dureeS < DUREE_MIN_S) return ctx.getString(R.string.err_min)
        val r = Drand.rondeActuelle() ?: return ctx.getString(R.string.err_reseau)
        e.rondeCible = nouvelleCible(r, e.rondeCible, dureeS)
        e.rondeLiberation = 0 // une prolongation annule toute demande de sortie
        return null
    }

    @Synchronized
    fun demanderSortie(ctx: Context): String? {
        val e = Etat(ctx)
        if (!e.verrouActif) return ctx.getString(R.string.err_aucun)
        if (e.rondeLiberation > 0) return ctx.getString(R.string.err_demande_cours)
        val r = Drand.rondeActuelle() ?: return ctx.getString(R.string.err_reseau)
        if (r < e.rondeCible) return ctx.getString(R.string.err_pas_echeance)
        e.rondeLiberation = r + e.delaiRondes
        return null
    }

    @Synchronized
    fun annulerDemande(ctx: Context): String? {
        Etat(ctx).rondeLiberation = 0
        return null
    }

    @Synchronized
    fun liberer(ctx: Context): String? {
        val e = Etat(ctx)
        if (!e.verrouActif) return ctx.getString(R.string.err_aucun)
        if (e.rondeLiberation <= 0) return ctx.getString(R.string.err_demande_dabord)
        val r = Drand.rondeActuelle() ?: return ctx.getString(R.string.err_reseau)
        if (r < e.rondeLiberation) return ctx.getString(R.string.err_reflexion)
        Protections.retirer(ctx)
        e.verrouActif = false
        e.rondeCible = 0
        e.rondeLiberation = 0
        return null
    }
}
