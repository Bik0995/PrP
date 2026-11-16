package org.prp.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Browser
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.prp.android.filtre.Filtre
import org.prp.android.filtre.Verdict
import java.util.concurrent.Executors

/**
 * La garde : lit l'adresse affichée par le navigateur au premier plan et, si le site est
 * visé, le remplace par une page sûre puis affiche l'écran « Site bloqué ».
 * Elle fonctionne quel que soit le chemin réseau (Wi-Fi, données mobiles, VPN comme Happ,
 * navigation privée) puisqu'elle regarde ce que le navigateur montre, pas le trafic.
 * Elle héberge aussi le cycle du gardien, car Android maintient ce service en vie.
 */
class GardeService : AccessibilityService() {

    private val fond = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var filtre: Filtre? = null
    @Volatile private var navigateurs: Set<String> = emptySet()
    private var examenPrevu = false
    private var dernierHote = ""
    private var dernierBlocage = 0L
    private var recepteurInscrit = false

    private val examen = Runnable {
        examenPrevu = false
        examiner()
    }

    private val tic = object : Runnable {
        override fun run() {
            fond.execute { Gardien.cycle(this@GardeService, avecDns = true) }
            principal.postDelayed(this, 60_000L)
        }
    }

    private val paquets = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            fond.execute {
                navigateurs = listerNavigateurs()
                Gardien.cycle(this@GardeService, avecDns = false)
            }
        }
    }

    override fun onServiceConnected() {
        fond.execute {
            navigateurs = listerNavigateurs()
            filtre = try {
                Filtre(assets.open("nsfw.bin").use { Filtre.chargerListe(it) })
            } catch (e: Exception) {
                Filtre(emptySet()) // la liste manque : les mots-clés restent actifs
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(paquets, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(paquets, f)
        recepteurInscrit = true
        Gardien.programmer(this)
        principal.post(tic)
    }

    /** Toutes les applications capables d'ouvrir une page web quelconque = les navigateurs. */
    private fun listerNavigateurs(): Set<String> = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet() + IDS_BARRE.keys
    } catch (e: Exception) { IDS_BARRE.keys }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val paquet = event?.packageName?.toString() ?: return
        if (filtre == null || paquet == packageName || paquet !in navigateurs) return
        // Un seul examen à la fois, légèrement différé : on lit l'état final de la page.
        if (!examenPrevu) {
            examenPrevu = true
            principal.postDelayed(examen, 150L)
        }
    }

    private fun examiner() {
        val f = filtre ?: return
        val racine = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val paquet = racine.packageName?.toString() ?: return
        if (paquet !in navigateurs) return
        val texte = try { lireBarre(racine, paquet) } catch (e: Exception) { null } ?: return
        val v = f.verifierUrl(texte)
        if (v.bloque) bloquer(paquet, v)
    }

    /** Texte de la barre d'adresse, ou null si elle est introuvable ou en cours de frappe. */
    private fun lireBarre(racine: AccessibilityNodeInfo, paquet: String): String? {
        val ids = (IDS_BARRE[paquet] ?: emptyList()) + "$paquet:id/url_bar"
        for (id in ids) {
            for (n in racine.findAccessibilityNodeInfosByViewId(id)) {
                if (n.isFocused) return null
                val t = n.text?.toString()
                if (!t.isNullOrBlank()) return t
            }
        }
        // Navigateur inconnu : on cherche un champ de saisie qui contient une adresse.
        val file = ArrayDeque<AccessibilityNodeInfo>()
        file.add(racine)
        var vus = 0
        while (file.isNotEmpty() && vus < 400) {
            val n = file.removeFirst()
            vus++
            if (n.className?.toString() == "android.widget.EditText" && !n.isFocused) {
                val t = n.text?.toString()
                if (!t.isNullOrBlank() && Filtre.extraireHote(t) != null) return t
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { file.add(it) }
        }
        return null
    }

    private fun bloquer(paquet: String, v: Verdict) {
        val maintenant = SystemClock.uptimeMillis()
        if (v.hote == dernierHote && maintenant - dernierBlocage < 1500L) return
        dernierHote = v.hote
        dernierBlocage = maintenant
        try {
            // remplace la page dans le même onglet du même navigateur
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PAGE_SURE))
                    .setPackage(paquet)
                    .putExtra(Browser.EXTRA_APPLICATION_ID, paquet)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        principal.postDelayed({
            try {
                startActivity(
                    Intent(this, BloqueActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(BloqueActivity.HOTE, v.hote)
                        .putExtra(BloqueActivity.RAISON, v.raison?.libelle ?: ""),
                )
            } catch (e: Exception) { /* l'essentiel est fait : la page a été remplacée */ }
        }, 300L)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        arreter()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        arreter()
        super.onDestroy()
    }

    /** Si quelqu'un coupe la garde pendant le verrou, elle se réactive aussitôt. */
    private fun arreter() {
        principal.removeCallbacksAndMessages(null)
        if (recepteurInscrit) {
            recepteurInscrit = false
            try { unregisterReceiver(paquets) } catch (e: Exception) { }
        }
        try {
            if (Etat(this).verrouActif) {
                val app = applicationContext
                principal.postDelayed({ Gardien.reactiverGarde(app) }, 500L)
            }
        } catch (e: Exception) { }
    }

    companion object {
        const val PAGE_SURE = "https://www.google.com/"

        /** Identifiant de la barre d'adresse des navigateurs courants. */
        val IDS_BARRE: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title",
            ),
            "org.mozilla.focus" to listOf("org.mozilla.focus:id/mozac_browser_toolbar_url_view"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.yandex.browser" to listOf(
                "com.yandex.browser:id/bro_omnibar_address_title_text",
                "com.yandex.browser:id/bro_omnibox_collapsed_title",
            ),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            ),
            "com.duckduckgo.mobile.android" to listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput",
            ),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
            "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
            "com.mi.globalbrowser" to listOf("com.mi.globalbrowser:id/url"),
        )
    }
}
