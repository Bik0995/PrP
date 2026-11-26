package org.prp.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import org.prp.android.filtre.Raison
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
    private val lecteur = Executors.newSingleThreadExecutor() // lecture du texte, hors du fil principal
    private val lectureEnCours = java.util.concurrent.atomic.AtomicBoolean(false)
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
            groupes = Etat(this).groupesAppris
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
        if (paquet in APPLIS && paquet !in navigateurs) { surAppli(event, paquet); return }
        if (filtre == null || paquet == packageName || paquet !in navigateurs) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) { saisieNavigateur(event, paquet); return }
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
        if (v.bloque) { bloquer(paquet, v); return }
        // page de résultats d'un moteur de recherche (y compris en navigation privée)
        // certains navigateurs n'affichent que les mots cherchés à la place de l'adresse
        val q = AnalyseTexte.requeteDansUrl(texte) ?: texte.takeIf { Filtre.extraireHote(it) == null }
        if (q != null && AnalyseTexte.rechercheAdulte(q)) bloquer(paquet, Verdict(true, q, Raison.RECHERCHE))
        else if (q != null && AnalyseTexte.rechercheSensible(q)) detection?.vigilance(paquet)
    }

    /** Ce qu'on tape dans la barre d'adresse ou dans le champ de recherche d'une page. */
    private fun saisieNavigateur(event: AccessibilityEvent, paquet: String) {
        val n = event.source ?: return
        val id = n.viewIdResourceName.orEmpty()
        val barre = id.endsWith("url_bar") || IDS_BARRE[paquet]?.contains(id) == true
        if (!barre && !estRecherche(n)) return
        val q = (n.text ?: event.text.joinToString(" ")).toString()
        if (Filtre.extraireHote(q) != null) return // une adresse : la garde des adresses s'en charge
        if (!AnalyseTexte.rechercheAdulte(q)) {
            if (AnalyseTexte.rechercheSensible(q)) detection?.vigilance(paquet)
            return
        }
        effacer(n)
        bloquer(paquet, Verdict(true, q, Raison.RECHERCHE))
    }

    private fun effacer(n: AccessibilityNodeInfo) {
        try {
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- texte de la page
    // Dans tous les onglets (y compris en navigation privée, où l'écran n'est pas visible),
    // le vocabulaire de la page est jugé : une page pornographique, même d'un site inconnu,
    // est bloquée et le site est retenu.
    private var lecturePrevue = false
    private var derniereLecture = ""
    private val lecture = Runnable {
        lecturePrevue = false
        // la page peut compter des milliers d'éléments : on la parcourt sur un autre fil,
        // une lecture à la fois, pour ne jamais retarder la garde de l'adresse
        if (lectureEnCours.compareAndSet(false, true)) lecteur.execute {
            try { lireTexte() } catch (e: Exception) { } finally { lectureEnCours.set(false) }
        }
    }

    private fun lireTexte() {
        val f = filtre ?: return
        val racine = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val paquet = racine.packageName?.toString() ?: return
        if (paquet !in navigateurs || paquet != paquetVu) return
        val h = hoteVu ?: return
        if (SystemClock.uptimeMillis() - instantVu > 20_000L) return // adresse trop ancienne : pas sûr du site
        if (AnalyseTexte.estInformation(h) || Filtre.estInstitution(h)) return
        val texte = lireNoeuds(racine)
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
        principal.post { bloquer(paquet, Verdict(true, h, Raison.TEXTE)) }
    }

    // ---------------------------------------------------------------- applications
    // Réseaux sociaux, messageries, vidéos : une recherche pour adultes est refusée dès qu'elle
    // est tapée, et un écran de résultats rempli de vocabulaire pornographique est quitté.
    // L'application elle-même n'est jamais bloquée.
    private var lectureAppliPrevue = false
    @Volatile private var appliLue = ""
    private var derniereLectureAppli = ""
    private var dernierBlocageAppli = 0L
    private val lectureAppli = Runnable {
        lectureAppliPrevue = false
        val p = appliLue
        if (lectureEnCours.compareAndSet(false, true)) lecteur.execute {
            try { lireAppli(p) } catch (e: Exception) { } finally { lectureEnCours.set(false) }
        }
    }

    private fun surAppli(event: AccessibilityEvent, paquet: String) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val n = event.source ?: return
            if (!estRecherche(n)) return
            val q = (n.text ?: event.text.joinToString(" ")).toString()
            if (!AnalyseTexte.rechercheAdulte(q, sociale = true)) {
                if (AnalyseTexte.rechercheSensible(q)) detection?.vigilance(paquet)
                return
            }
            effacer(n)
            bloquerAppli(paquet, Raison.RECHERCHE)
            return
        }
        appliLue = paquet
        if (!lectureAppliPrevue) {
            lectureAppliPrevue = true
            principal.postDelayed(lectureAppli, 1_500L)
        }
    }

    /** Champ de recherche (et non zone de message) : reconnu à son identifiant ou à son indication. */
    private fun estRecherche(n: AccessibilityNodeInfo): Boolean {
        val classe = n.className?.toString().orEmpty()
        if (!n.isEditable && "EditText" !in classe && "Search" !in classe) return false
        if ("Search" in classe) return true
        val id = n.viewIdResourceName.orEmpty().lowercase()
        if ("search" in id || "query" in id) return true
        val indice = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 26) n.hintText else null, n.contentDescription,
        ).joinToString(" ").lowercase()
        return MOTS_RECHERCHE.any { it in indice }
    }

    private fun lireAppli(paquet: String) {
        val racine = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (racine.packageName?.toString() != paquet) return
        if (paquet in MESSAGERIES && examinerConversation(racine, paquet)) return
        val texte = lireNoeuds(racine)
        if (texte == derniereLectureAppli) return
        derniereLectureAppli = texte
        if (AnalyseTexte.evaluer(texte).adulte) principal.post { bloquerAppli(paquet, Raison.CONTENU) }
    }

    private fun lireNoeuds(racine: AccessibilityNodeInfo): String {
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
        return sb.toString()
    }

    // ---------------------------------------------------------------- groupes et conversations
    // Nom de la conversation ouverte dans une messagerie. Un groupe dont le nom est pornographique
    // est quitté dès son ouverture ; un groupe où plusieurs images ont été floutées est retenu.
    @Volatile private var groupes: Set<String> = emptySet()
    @Volatile private var titreVu: String? = null
    @Volatile private var paquetTitre = ""
    @Volatile private var instantTitre = 0L

    /** Vrai si la conversation a été quittée. */
    private fun examinerConversation(racine: AccessibilityNodeInfo, paquet: String): Boolean {
        val titre = titreConversation(racine, paquet)
        titreVu = titre
        paquetTitre = paquet
        instantTitre = SystemClock.uptimeMillis()
        if (titre == null) return false
        if (cleGroupe(paquet, titre) in groupes || AnalyseTexte.rechercheAdulte(titre, sociale = true)) {
            principal.post { bloquerAppli(paquet, Raison.GROUPE) }
            return true
        }
        return false
    }

    private fun cleGroupe(paquet: String, titre: String) = "$paquet|${titre.lowercase()}"

    /** Titre en haut de l'écran (nom du contact, du groupe ou de la chaîne), ou null. */
    private fun titreConversation(racine: AccessibilityNodeInfo, paquet: String): String? {
        var t: String? = null
        for (id in IDS_TITRE[paquet].orEmpty()) {
            t = racine.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) break
        }
        if (t.isNullOrEmpty()) {
            val m = resources.displayMetrics
            val r = android.graphics.Rect()
            val file = ArrayDeque<AccessibilityNodeInfo>()
            file.add(racine)
            var vus = 0
            while (file.isNotEmpty() && vus < 300 && t.isNullOrEmpty()) {
                val n = file.removeFirst()
                vus++
                val x = n.text?.toString()?.trim()
                if (!x.isNullOrEmpty() && !n.isEditable) {
                    n.getBoundsInScreen(r)
                    if (r.top > m.heightPixels * 0.02f && r.top < m.heightPixels * 0.12f && r.width() > m.widthPixels * 0.2f)
                        t = x
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { file.add(it) }
            }
        }
        val titre = t ?: return null
        if (titre.length !in 2..80) return null
        val bas = titre.lowercase()
        if (bas in TITRES_GENERIQUES) return null
        val nomAppli = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquet, 0)).toString().lowercase()
        } catch (e: Exception) { "" }
        return if (bas == nomAppli) null else titre
    }

    /** Images floutées dans une conversation : au bout de plusieurs, elle est retenue et quittée. */
    private fun groupeFloute(paquet: String, nombre: Int) {
        val t = titreVu ?: return
        val maintenant = SystemClock.uptimeMillis()
        if (paquet != paquetTitre || maintenant - instantTitre > 60_000L) return
        val cle = cleGroupe(paquet, t)
        if (!apprentissage.signalerCle(cle, nombre, maintenant)) return
        groupes = groupes + cle
        fond.execute {
            val e = Etat(this)
            e.groupesAppris = e.groupesAppris + cle
        }
        bloquerAppli(paquet, Raison.GROUPE)
    }

    /** Revient à l'écran précédent de l'application et affiche « Contenu bloqué ». */
    private fun bloquerAppli(paquet: String, raison: Raison) {
        val maintenant = SystemClock.uptimeMillis()
        if (maintenant - dernierBlocageAppli < 1500L) return
        dernierBlocageAppli = maintenant
        derniereLectureAppli = ""
        performGlobalAction(GLOBAL_ACTION_BACK)
        val nom = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquet, 0)).toString()
        } catch (e: Exception) { paquet }
        principal.postDelayed({
            try {
                startActivity(
                    Intent(this, BloqueActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(BloqueActivity.HOTE, nom)
                        .putExtra(BloqueActivity.RAISON, raison.name),
                )
            } catch (e: Exception) { }
        }, 300L)
    }

    // ---------------------------------------------------------------- apprentissage
    // Site affiché en dernier dans un navigateur, pour savoir où des images ont été floutées.
    @Volatile private var hoteVu: String? = null
    @Volatile private var paquetVu = ""
    @Volatile private var instantVu = 0L
    private val apprentissage = Apprentissage()

    /** Plusieurs images pornographiques floutées sur un même site : le site entier est bloqué. */
    private fun imagesFloutees(paquet: String, nombre: Int) {
        if (paquet in MESSAGERIES) { groupeFloute(paquet, nombre); return }
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
        groupes = emptySet()
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

        /** Applications où l'on cherche et voit du contenu : réseaux sociaux, messageries, vidéos. */
        val APPLIS = setOf(
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.instagram.android",
            "com.instagram.lite", "com.instagram.barcelona", "org.telegram.messenger",
            "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
            "com.vkontakte.android", "com.vk.im", "ru.ok.android", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage",
            "com.pinterest", "com.snapchat.android", "com.tumblr", "com.google.android.youtube",
            "com.google.android.googlequicksearchbox", "ru.yandex.searchplugin", "com.discord",
            "com.bsky.app", "ru.rutube.app", "ru.dzen.app", "com.whatsapp", "com.imo.android.imoim",
            "jp.naver.line.android", "com.kakao.talk", "com.tencent.mm", "com.sina.weibo",
            "tv.danmaku.bili", "com.xingin.xhs", "com.ss.android.ugc.aweme", "com.twitch.android.app",
            "com.whatsapp.w4b", "org.thoughtcrime.securesms", "com.viber.voip", "org.telegram.messenger.beta",
        )

        /** Messageries et applications à groupes ou chaînes. */
        val MESSAGERIES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.telegram.messenger.beta", "org.telegram.plus", "org.thunderdog.challegram", "com.facebook.orca",
            "com.vk.im", "com.discord", "org.thoughtcrime.securesms", "com.viber.voip", "com.imo.android.imoim",
            "jp.naver.line.android", "com.kakao.talk", "com.tencent.mm",
        )

        /** Identifiant du titre de la conversation, quand on le connaît. */
        val IDS_TITRE: Map<String, List<String>> = mapOf(
            "com.whatsapp" to listOf("com.whatsapp:id/conversation_contact_name"),
            "com.whatsapp.w4b" to listOf("com.whatsapp.w4b:id/conversation_contact_name"),
            "com.viber.voip" to listOf("com.viber.voip:id/toolbar_title"),
        )

        /** Titres des écrans généraux (liste des discussions, appels, réglages…) : jamais retenus. */
        val TITRES_GENERIQUES = setOf(
            "chats", "discussions", "чаты", "сообщения", "messages", "calls", "appels", "звонки", "contacts",
            "контакты", "settings", "paramètres", "настройки", "status", "statut", "статус", "communities",
            "communautés", "сообщества", "updates", "actus", "actualités", "обновления", "archived", "archivées",
            "архив", "telegram", "whatsapp", "messenger", "discord", "signal", "viber", "search", "rechercher",
            "поиск", "connecting...", "connecting…", "updating...", "updating…", "waiting for network...",
            "соединение...", "соединение…", "обновление...", "обновление…", "ожидание сети...", "connexion...",
            "connexion…", "mise à jour...", "new group", "nouveau groupe", "новая группа", "stories", "chaînes", "channels", "каналы",
        )

        /** Indication d'un champ de recherche, dans les langues courantes. */
        val MOTS_RECHERCHE = listOf(
            "search", "recherch", "поиск", "найти", "искать", "buscar", "busca", "pesquisar", "suche",
            "cerca", "ricerca", "szukaj", "zoeken", "بحث", "חיפוש", "搜索", "搜尋", "検索", "검색",
            "खोज", "ค้นหา", "tìm", "cari", "пошук",
        )
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
