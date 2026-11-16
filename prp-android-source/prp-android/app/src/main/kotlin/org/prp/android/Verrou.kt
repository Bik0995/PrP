package org.prp.android

import android.content.Context

/**
 * Le verrou à sens unique. Chaque fonction renvoie null si tout s'est bien passé,
 * sinon un message d'erreur à montrer. Toutes interrogent drand : à appeler hors du fil principal.
 */
object Verrou {
    private const val SANS_RESEAU =
        "Impossible de joindre les relais drand. Vérifie la connexion Internet et réessaie."
    const val DUREE_MIN_S = 60L

    /** Règle centrale : une échéance ne peut que reculer, jamais avancer. */
    fun nouvelleCible(rondeActuelle: Long, cibleExistante: Long, dureeS: Long): Long =
        maxOf(cibleExistante, rondeActuelle + Drand.rondes(dureeS))

    @Synchronized
    fun verrouiller(ctx: Context, dureeS: Long, delaiS: Long): String? {
        val e = Etat(ctx)
        if (e.verrouActif) return "Le verrou est déjà actif."
        if (!Protections.estProprietaire(ctx)) return "PrP n'est pas encore propriétaire de l'appareil."
        if (dureeS < DUREE_MIN_S || delaiS < DUREE_MIN_S) return "La durée minimale est d'une minute."
        val r = Drand.rondeActuelle() ?: return SANS_RESEAU
        e.rondeCible = nouvelleCible(r, 0, dureeS)
        e.delaiRondes = Drand.rondes(delaiS)
        e.rondeLiberation = 0
        e.verrouActif = true
        Gardien.cycle(ctx, avecDns = true)
        Gardien.programmer(ctx)
        return null
    }

    @Synchronized
    fun prolonger(ctx: Context, dureeS: Long): String? {
        val e = Etat(ctx)
        if (!e.verrouActif) return "Aucun verrou actif."
        if (dureeS < DUREE_MIN_S) return "La durée minimale est d'une minute."
        val r = Drand.rondeActuelle() ?: return SANS_RESEAU
        e.rondeCible = nouvelleCible(r, e.rondeCible, dureeS)
        e.rondeLiberation = 0 // une prolongation annule toute demande de sortie
        return null
    }

    @Synchronized
    fun demanderSortie(ctx: Context): String? {
        val e = Etat(ctx)
        if (!e.verrouActif) return "Aucun verrou actif."
        if (e.rondeLiberation > 0) return "Une demande est déjà en cours."
        val r = Drand.rondeActuelle() ?: return SANS_RESEAU
        if (r < e.rondeCible) return "L'échéance du verrou n'est pas encore atteinte."
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
        if (!e.verrouActif) return "Aucun verrou actif."
        if (e.rondeLiberation <= 0) return "Demande d'abord la sortie."
        val r = Drand.rondeActuelle() ?: return SANS_RESEAU
        if (r < e.rondeLiberation) return "Le délai de réflexion n'est pas terminé."
        Protections.retirer(ctx)
        e.verrouActif = false
        e.rondeCible = 0
        e.rondeLiberation = 0
        return null
    }
}
