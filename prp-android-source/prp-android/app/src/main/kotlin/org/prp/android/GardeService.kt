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
import android.widget.Toast
import org.prp.android.filtre.AnalyseTexte
import org.prp.android.filtre.Apprentissage
import org.prp.android.filtre.Filtre
import org.prp.android.filtre.ListeSites
import org.prp.android.detection.Detection
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
    private var detection: Detection? = null

    // Mode Simple pendant le verrou : les pages qui permettraient de retirer PrP sont fermées.
    private var lectureMode = 0L
    private var simpleVerrouille = false
    private var reglagePrevu: String? = null
    private var dernierRefus = 0L
    private val examenReglages = Runnable {
        val p = reglagePrevu
        reglagePrevu = null
        if (p != null) examinerReglages(p)
    }

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
                Filtre(assets.open("sites.bin").use { ListeSites.charger(it) }, Etat(this).sitesAppris)
            } catch (e: Exception) {
                Filtre(ListeSites.VIDE) // la liste manque : les mots-clés restent actifs
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
        instance = this
        detection = Detection(this) { paquet, n -> imagesFloutees(paquet, n) }
        majDetection()
    }

    /** Démarre ou arrête la détection d'images selon le réglage. */
    fun majDetection() {
        val d = detection ?: return
        if (Etat(this).detectionActive) d.demarrer() else d.arreter()
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
        if (estSensible(paquet) && estSimpleVerrouille() &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            if (reglagePrevu == null) principal.postDelayed(examenReglages, 120L)
            reglagePrevu = paquet
        }
        if (paquet != packageName) when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> detection?.surDefilement()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> detection?.surContenu()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (paquet != packageName || event.className?.toString()?.endsWith("Activity") == true)
        ) detection?.surPaquet(paquet)
        if (filtre == null || paquet == packageName || paquet !in navigateurs) return
        // Lecture du texte de la page : au plus une fois par seconde et demie, après les changements.
        if (!lecturePrevue) {
            lecturePrevue = true
            principal.postDelayed(lecture, 1_500L)
        }
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
        Filtre.extraireHote(texte)?.let {
            hoteVu = it
            paquetVu = paquet
            instantVu = SystemClock.uptimeMillis()
        }
        val v = f.verifierUrl(texte)
        if (v.bloque) bloquer(paquet, v)
    }

    // ---------------------------------------------------------------- texte de la page
    // Dans tous les onglets (y compris en navigation privée, où l'écran n'est pas visible),
    // le vocabulaire de la page est jugé : une page pornographique, même d'un site inconnu,
    // est bloquée et le site est retenu.
    private var lecturePrevue = false
    private var derniereLecture = ""
    private val lecture = Runnable {
        lecturePrevue = false
        try { lireTexte() } catch (e: Exception) { }
    }

    private fun lireTexte() {
        val f = filtre ?: return
        val racine = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val paquet = racine.packageName?.toString() ?: return
        if (paquet !in navigateurs || paquet != paquetVu) return
        val h = hoteVu ?: return
        if (AnalyseTexte.estInformation(h) || Filtre.estInstitution(h)) return
        val sb = StringBuilder()
        val pile = ArrayDeque<AccessibilityNodeInfo>()
        pile.add(racine)
        var vus = 0
        while (pile.isNotEmpty() && vus < 2500 && sb.length < 30_000) {
            val n = pile.removeLast()
            vus++
            n.text?.let { if (it.length < 400) sb.append(it).append(' ') }
            n.contentDescription?.let { if (it.length < 400) sb.append(it).append(' ') }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { pile.add(it) }
        }
        val texte = sb.toString()
        if (texte == derniereLecture) return
        derniereLecture = texte
        val r = AnalyseTexte.evaluer(texte)
        if (!r.adulte) return
        val appris = Apprentissage.nettoyer(h) ?: return
        if (Apprentissage.estPlateforme(appris)) return
        f.apprendre(appris)
        fond.execute {
            val e = Etat(this)
            e.sitesAppris = e.sitesAppris + appris
        }
        bloquer(paquet, Verdict(true, h, Raison.TEXTE))
    }

    // ---------------------------------------------------------------- apprentissage
    // Site affiché en dernier dans un navigateur, pour savoir où des images ont été floutées.
    private var hoteVu: String? = null
    private var paquetVu = ""
    private var instantVu = 0L
    private val apprentissage = Apprentissage()

    /** Plusieurs images pornographiques floutées sur un même site : le site entier est bloqué. */
    private fun imagesFloutees(paquet: String, nombre: Int) {
        val h = hoteVu ?: return
        val maintenant = SystemClock.uptimeMillis()
        if (paquet != paquetVu || paquet !in navigateurs || maintenant - instantVu > 60_000L) return
        val appris = apprentissage.signaler(h, nombre, maintenant) ?: return
        val f = filtre ?: return
        f.apprendre(appris)
        fond.execute {
            val e = Etat(this)
            e.sitesAppris = e.sitesAppris + appris
        }
        val v = f.verifierHote(h)
        if (v.bloque) bloquer(paquet, v)
    }

    /** Oublie les sites appris (seulement hors verrou, depuis l'écran de PrP). */
    fun oublierAppris() {
        filtre?.oublierAppris()
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

    private fun estSensible(paquet: String) = paquet in REGLAGES || "packageinstaller" in paquet

    private fun estSimpleVerrouille(): Boolean {
        val t = SystemClock.uptimeMillis()
        if (t - lectureMode > 3000L) {
            lectureMode = t
            simpleVerrouille = try {
                Etat(this).verrouActif && !Protections.estProprietaire(this)
            } catch (e: Exception) { false }
        }
        return simpleVerrouille
    }

    /** Ferme la page si elle concerne PrP (fiche de l'application, garde, administrateur, désinstallation). */
    private fun examinerReglages(paquet: String) {
        val racine = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (racine.packageName?.toString() != paquet) return
        val installeur = "packageinstaller" in paquet
        var visee = false
        val file = ArrayDeque<AccessibilityNodeInfo>()
        file.add(racine)
        var vus = 0
        while (file.isNotEmpty() && vus < 600 && !visee) {
            val n = file.removeFirst()
            vus++
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                if (t in NOMS_GARDE) visee = true
                else if (installeur && "PrP" in t) visee = true
                else if (t == "PrP") {
                    val id = n.viewIdResourceName.orEmpty()
                    val parent = try { n.parent?.className?.toString().orEmpty() } catch (e: Exception) { "" }
                    if (IDS_ENTETE.any { it in id } || "Toolbar" in parent) visee = true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { file.add(it) }
        }
        if (!visee) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val maintenant = SystemClock.uptimeMillis()
        if (maintenant - dernierRefus > 3000L) {
            dernierRefus = maintenant
            Toast.makeText(this, getString(R.string.reglage_ferme), Toast.LENGTH_LONG).show()
        }
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
                        .putExtra(BloqueActivity.RAISON, v.raison?.name ?: ""),
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
        detection?.fermer()
        if (instance === this) instance = null
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
        @Volatile var instance: GardeService? = null
        const val PAGE_SURE = "https://www.google.com/"

        /** Applications de réglages où l'on peut arrêter, désactiver ou désinstaller PrP. */
        val REGLAGES = setOf(
            "com.android.settings", "com.samsung.android.settings", "com.miui.securitycenter",
            "com.coloros.safecenter", "com.oplus.safecenter", "com.huawei.systemmanager",
            "com.samsung.android.lool", "com.miui.cleanmaster",
        )

        /** Nom de la garde dans les réglages d'accessibilité (dans les trois langues). */
        val NOMS_GARDE = setOf("PrP – Garde", "PrP – Guard", "PrP – Защита")

        /** Identifiants des titres de page où le nom d'une application désigne la page elle-même. */
        val IDS_ENTETE = listOf(
            "entity_header_title", "admin_name", "alertTitle", "app_name", "app_title",
            "title_bar", "action_bar_title", "header_title", "app_detail_name",
        )

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
