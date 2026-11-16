package org.prp.android

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.UserManager

/**
 * Les couches système de PrP, posées grâce au rôle de propriétaire de l'appareil.
 * `appliquer` peut être rappelée sans fin : elle remet simplement tout en place.
 */
object Protections {

    /** Résolveurs DNS chiffrés (DoT) qui filtrent le contenu adulte, par ordre de préférence. */
    val HOTES_DNS = listOf(
        "family.adguard-dns.com",
        "family.cloudflare-dns.com",
        "adult-filter-dns.cleanbrowsing.org",
    )

    /** Navigateurs de la famille Chrome qui acceptent une configuration imposée. */
    private val NAVIGATEURS_GERES = listOf(
        "com.android.chrome", "com.chrome.beta", "com.microsoft.emmx",
        "com.brave.browser", "com.vivaldi.browser", "com.yandex.browser",
    )

    /** Applications dont le seul rôle est de sortir du réseau normal (Tor) : suspendues. */
    private val CONTOURNEMENTS = listOf(
        "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
        "org.torproject.android", "info.guardianproject.orfox",
    )

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun estProprietaire(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Version d'essai (testOnly) : on garde le débogage USB comme porte de secours. */
    fun estEssai(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0

    private fun restrictions(ctx: Context): List<String> {
        val l = mutableListOf(
            UserManager.DISALLOW_FACTORY_RESET,        // pas de « Réinitialiser le téléphone » depuis les réglages
            UserManager.DISALLOW_SAFE_BOOT,            // pas de mode sans échec (qui coupe les applications)
            UserManager.DISALLOW_ADD_USER,             // pas de second utilisateur ni d'invité
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,  // pas de profil professionnel
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,   // le DNS privé ne se change plus
            UserManager.DISALLOW_NETWORK_RESET,
        )
        if (Build.VERSION.SDK_INT >= 35) l.add("no_add_private_profile") // « espace privé » d'Android 15
        if (!estEssai(ctx)) l.add(UserManager.DISALLOW_DEBUGGING_FEATURES) // adb pourrait tout défaire
        return l
    }

    private fun installe(ctx: Context, paquet: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(paquet, 0); true
    } catch (e: Exception) { false }

    @Synchronized
    fun appliquer(ctx: Context, avecDns: Boolean) {
        if (!estProprietaire(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        for (r in restrictions(ctx)) essayer { d.addUserRestriction(a, r) }
        if (Build.VERSION.SDK_INT >= 30) essayer { d.setUserControlDisabledPackages(a, listOf(ctx.packageName)) }

        val cfg = Bundle().apply {
            putString("DnsOverHttpsMode", "off")       // le navigateur doit utiliser le DNS du système
            putBoolean("ForceGoogleSafeSearch", true)
            putInt("ForceYouTubeRestrict", 2)
        }
        for (n in NAVIGATEURS_GERES) if (installe(ctx, n)) essayer { d.setApplicationRestrictions(a, n, cfg) }

        val tor = CONTOURNEMENTS.filter { installe(ctx, it) }
        if (tor.isNotEmpty()) essayer { d.setPackagesSuspended(a, tor.toTypedArray(), true) }

        if (avecDns) essayer { reglerDns(ctx, d, a) }
    }

    /**
     * DNS privé strict vers un résolveur familial. Si l'hôte actuel ne répond plus, on passe
     * au suivant. Si AUCUN ne répond alors qu'il y a du réseau (port 853 bloqué par
     * l'opérateur, par exemple), on repasse en mode automatique pour ne pas couper Internet :
     * la garde des navigateurs reste active, et on réessaie au cycle suivant.
     */
    private fun reglerDns(ctx: Context, d: DevicePolicyManager, a: ComponentName) {
        val etat = Etat(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val reseau = cm.activeNetwork ?: return           // hors ligne : on ne touche à rien
        val caps = cm.getNetworkCapabilities(reseau) ?: return
        val valide = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val hote = d.getGlobalPrivateDnsHost(a)
        val strict = d.getGlobalPrivateDnsMode(a) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
            hote in HOTES_DNS
        if (strict && valide) { etat.echecsReseau = 0; etat.dnsDegrade = false; return }
        if (strict) {
            etat.echecsReseau = etat.echecsReseau + 1
            if (etat.echecsReseau < 2) return               // on laisse une chance avant de changer
        }
        val depart = if (strict) HOTES_DNS.indexOf(hote) + 1 else 0
        for (k in HOTES_DNS.indices) {
            val h = HOTES_DNS[(depart + k) % HOTES_DNS.size]
            val res = try { d.setGlobalPrivateDnsModeSpecifiedHost(a, h) } catch (e: Exception) { -1 }
            if (res == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                etat.echecsReseau = 0; etat.dnsDegrade = false
                return
            }
        }
        d.setGlobalPrivateDnsModeOpportunistic(a)
        etat.dnsDegrade = true
    }

    @Synchronized
    fun retirer(ctx: Context) {
        if (!estProprietaire(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        val toutes = restrictions(ctx) + UserManager.DISALLOW_DEBUGGING_FEATURES
        for (r in toutes) essayer { d.clearUserRestriction(a, r) }
        if (Build.VERSION.SDK_INT >= 30) essayer { d.setUserControlDisabledPackages(a, emptyList()) }
        for (n in NAVIGATEURS_GERES) if (installe(ctx, n)) essayer { d.setApplicationRestrictions(a, n, Bundle()) }
        val tor = CONTOURNEMENTS.filter { installe(ctx, it) }
        if (tor.isNotEmpty()) essayer { d.setPackagesSuspended(a, tor.toTypedArray(), false) }
        essayer { d.setGlobalPrivateDnsModeOpportunistic(a) }
    }

    /** Abandonne le rôle de propriétaire (après libération), pour permettre la désinstallation. */
    @Suppress("DEPRECATION")
    fun abandonnerRole(ctx: Context) {
        if (!estProprietaire(ctx)) return
        val d = dpm(ctx)
        essayer { d.clearDeviceOwnerApp(ctx.packageName) }
        essayer { d.removeActiveAdmin(admin(ctx)) }
    }

    /** État de chaque couche, pour l'écran principal. */
    fun diagnostic(ctx: Context): List<Pair<String, Boolean>> {
        val d = dpm(ctx)
        val a = admin(ctx)
        val proprio = estProprietaire(ctx)
        val dnsStrict = proprio && try {
            d.getGlobalPrivateDnsMode(a) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                d.getGlobalPrivateDnsHost(a) in HOTES_DNS
        } catch (e: Exception) { false }
        val verrous = proprio && try {
            val b = d.getUserRestrictions(a)
            b.getBoolean(UserManager.DISALLOW_FACTORY_RESET) && b.getBoolean(UserManager.DISALLOW_SAFE_BOOT)
        } catch (e: Exception) { false }
        return listOf(
            "Propriétaire de l'appareil" to proprio,
            "Garde des navigateurs" to Gardien.gardeActive(ctx),
            "Réactivation automatique de la garde" to Gardien.peutReactiver(ctx),
            "DNS privé familial (strict)" to dnsStrict,
            "Réglages système verrouillés" to verrous,
        )
    }

    private inline fun essayer(f: () -> Unit) {
        try { f() } catch (e: Exception) { /* couche indisponible sur cet appareil : on continue */ }
    }
}
