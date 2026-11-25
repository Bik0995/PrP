package org.prp.android

import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Mode Fort sans PC : PrP devient propriétaire de l'appareil depuis le téléphone lui-même.
 *
 * Android 11 et plus permettent le « débogage sans fil » : l'application Shizuku s'en sert
 * pour exécuter des commandes adb sur le téléphone. PrP lui demande d'exécuter exactement ce
 * que faisait outils/installer-android.bat depuis le PC :
 *  1. vérifier qu'il n'y a qu'un utilisateur (pas de profil professionnel, d'espace clone…) ;
 *  2. retirer pour un instant les applications qui ont créé un compte (sauf Google, retiré à la main) ;
 *  3. `pm grant … WRITE_SECURE_SETTINGS` puis `dpm set-device-owner` ;
 *  4. remettre aussitôt les applications retirées, avec leurs données.
 */
object Installation {
    const val PAQUET_SHIZUKU = "moe.shizuku.privileged.api"
    private val NOM_PAQUET = Regex("^[A-Za-z0-9_.]+$")

    /** Comptes que PrP ne peut pas retirer lui-même (le service Google ne doit pas être touché). */
    private val PAQUETS_MANUELS = setOf("com.google.android.gms", "android", "com.android.settings")

    fun possible(): Boolean = Build.VERSION.SDK_INT >= 30

    fun shizukuInstalle(ctx: Context): Boolean = installe(ctx, PAQUET_SHIZUKU)

    fun shizukuActif(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun autorise(): Boolean = try {
        shizukuActif() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun demanderAutorisation() {
        try { Shizuku.requestPermission(CODE_DEMANDE) } catch (e: Throwable) { }
    }

    const val CODE_DEMANDE = 4242

    // ---------------------------------------------------------------- commandes

    /** Shizuku.newProcess n'est plus public dans l'API 13, mais existe toujours : appel par réflexion. */
    private fun processus(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    /** Exécute une commande avec les droits d'adb ; renvoie le code de sortie et tout le texte produit. */
    fun shell(commande: String): Pair<Int, String> {
        val p = processus(arrayOf("sh", "-c", commande))
        val sortie = StringBuilder()
        val lecteur = thread { sortie.append(p.inputStream.bufferedReader().readText()) }
        val erreurs = p.errorStream.bufferedReader().readText()
        lecteur.join()
        return p.waitFor() to (sortie.toString() + erreurs)
    }

    // ---------------------------------------------------------------- comptes

    class Compte(val type: String, val paquet: String?) {
        val manuel: Boolean get() = paquet == null || paquet in PAQUETS_MANUELS
    }

    class Bilan(val comptes: List<Compte>, val utilisateurs: Int) {
        val manuels: Int get() = comptes.count { it.manuel }
        val automatiques: Int get() = comptes.count { !it.manuel }
    }

    /** Types de comptes présents (une entrée par compte), lus avec les droits d'adb. */
    fun lireComptes(ctx: Context): List<Compte> {
        val texte = shell("dumpsys account").second
        val auteurs = try {
            AccountManager.get(ctx).authenticatorTypes.associate { it.type to it.packageName }
        } catch (e: Exception) { emptyMap() }
        return typesComptes(texte).map { Compte(it, auteurs[it]) }
    }

    /** Un type par compte listé dans « dumpsys account » (les doublons de la même ligne sont ignorés). */
    fun typesComptes(texte: String): List<String> =
        Regex("""Account \{name=[^\n]*?, type=([^}\n]+)\}""")
            .findAll(texte).map { it.value to it.groupValues[1].trim() }
            .distinctBy { it.first }
            .map { it.second }
            .toList()

    fun nbUtilisateurs(texte: String): Int = Regex("""UserInfo\{""").findAll(texte).count().coerceAtLeast(1)

    fun lireUtilisateurs(): Int = nbUtilisateurs(shell("pm list users").second)

    fun bilan(ctx: Context): Bilan = Bilan(lireComptes(ctx), lireUtilisateurs())

    // ---------------------------------------------------------------- procédure

    /** Renvoie null si PrP est devenu propriétaire de l'appareil, sinon un message à montrer. */
    @Synchronized
    fun devenirProprietaire(ctx: Context): String? {
        if (Protections.estProprietaire(ctx)) return null
        if (!autorise()) return ctx.getString(R.string.err_shizuku)

        if (lireUtilisateurs() > 1) return ctx.getString(R.string.err_utilisateurs)
        val comptes = lireComptes(ctx)
        val manuels = comptes.count { it.manuel }
        if (manuels > 0) return ctx.getString(R.string.err_comptes_manuels, manuels)

        val aRetirer = comptes.mapNotNull { it.paquet }.distinct()
            .filter { it != ctx.packageName && NOM_PAQUET.matches(it) }
        val e = Etat(ctx)
        // la liste est enregistrée AVANT de retirer quoi que ce soit : si PrP est interrompu,
        // les applications seront remises à la prochaine ouverture.
        e.paquetsRetires = (e.paquetsRetires + aRetirer).distinct()
        try {
            for (p in aRetirer) shell("pm uninstall -k --user 0 $p")
            val reste = lireComptes(ctx)
            if (reste.isNotEmpty()) {
                return ctx.getString(R.string.err_comptes_restants, reste.map { it.type }.distinct().joinToString(", "))
            }
            shell("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
            val (_, texte) = shell("dpm set-device-owner ${ctx.packageName}/.AdminReceiver")
            if (!Protections.estProprietaire(ctx)) {
                return ctx.getString(R.string.err_dpm, texte.trim().take(300))
            }
            return null
        } finally {
            restaurer(ctx)
            Gardien.reactiverGarde(ctx)
        }
    }

    /**
     * Remet les applications retirées pendant l'installation (données conservées).
     * Par Shizuku si possible, sinon par le rôle de propriétaire. Renvoie true si tout est revenu.
     */
    @Synchronized
    fun restaurer(ctx: Context): Boolean {
        val e = Etat(ctx)
        val liste = e.paquetsRetires
        if (liste.isEmpty()) return true
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        val restants = liste.filter { p ->
            if (!installe(ctx, p) && autorise()) {
                try { shell("cmd package install-existing $p") } catch (ex: Throwable) { }
            }
            if (!installe(ctx, p) && Protections.estProprietaire(ctx)) {
                try { dpm.installExistingPackage(admin, p) } catch (ex: Throwable) { }
            }
            !installe(ctx, p)
        }
        e.paquetsRetires = restants
        return restants.isEmpty()
    }

    fun installe(ctx: Context, paquet: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(paquet, 0); true
    } catch (e: Exception) { false }
}
