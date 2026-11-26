package org.prp.android.detection

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.prp.android.Etat
import java.util.concurrent.Executors

/** État de la détection, lisible par l'écran principal. */
enum class Analyse { AUCUNE, REFUSEE, NOIR, INCHANGE, RIEN, MASQUE }

object DiagDetection {
    @Volatile var disponible = false
    @Volatile var modeleCharge = false
    @Volatile var erreur: String? = null
    @Volatile var dernierMs = 0L
    /** Résultat de la dernière analyse (traduit par l'écran principal) et son détail éventuel. */
    @Volatile var dernier = Analyse.AUCUNE
    @Volatile var dernierDetail = ""
    @Volatile var analyses = 0
    @Volatile var ecransProteges = 0
    /** Nombre d'images actuellement floutées. */
    @Volatile var floutees = 0
}

/**
 * Détection d'images à l'écran : capture l'écran avec le service d'accessibilité, juge chaque
 * image affichée avec le modèle local (NSFWJS MobileNetV2, MIT), puis **floute l'image entière**
 * (ou la vidéo) qui la contient. Le flou suit l'image quand on fait défiler ; il disparaît
 * quand l'image sort de l'écran et revient dès qu'elle réapparaît. Si aucune image n'est
 * reconnue autour (vue dessinée par l'application), une large zone autour est floutée,
 * jusqu'au prochain défilement ou changement d'application.
 * Rien n'est enregistré ni envoyé : chaque capture est analysée en mémoire puis jetée.
 * Nécessite Android 11 minimum (capture d'écran par accessibilité).
 */
class Detection(
    private val service: AccessibilityService,
    /** Appelé (fil principal) quand des images sont floutées : application, nombre d'images. */
    private val surFlou: (paquet: String, nombre: Int) -> Unit = { _, _ -> },
) {
    private val principal = Handler(Looper.getMainLooper())
    private val fond = Executors.newSingleThreadExecutor()
    private val etat = Etat(service)
    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val ecran: PowerManager = service.getSystemService(PowerManager::class.java)
    private val verrouEcran: KeyguardManager = service.getSystemService(KeyguardManager::class.java)

    /**
     * Une image floutée. Avec un nœud d'accessibilité, elle suit l'image ; sans nœud (zone
     * de secours), elle reste en place jusqu'au prochain défilement.
     */
    private class Cible(
        val noeud: AccessibilityNodeInfo?,
        var zone: Zone,
        val flou: Bitmap?,
        val empreinte: String,
        var visible: Boolean = true,
        val etiquette: String? = null,
        val creee: Long = SystemClock.uptimeMillis(),
    )

    /** Dernier retrait d'un flou d'écran entier : s'il revient vite, un seul jugement suffit. */
    @Volatile private var dernierEcranRetire = 0L

    /** Version d'essai : une étiquette sur chaque flou dit pourquoi il est apparu. */
    private val essai = (service.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Images jugées pornographiques une fois : il faut une deuxième fois pour flouter (clé -> instant). */
    private val soupcons = HashMap<String, Long>()

    private val cibles = ArrayList<Cible>()
    private var vue: MasqueVue? = null
    private var vueAjoutee = false
    private var classifieur: Classifieur? = null
    /** Deuxième avis (Marqo) : consulté seulement pour les images que NSFWJS trouve suspectes. */
    private var arbitre: Classifieur? = null
    private var actif = false
    private var enCours = false
    private var derniereSignature: IntArray? = null
    private var derniereMaj = 0L
    private var paquetAvant = ""

    private val boucle = object : Runnable {
        override fun run() {
            if (!actif) return
            tic()
            principal.postDelayed(this, 900L)
        }
    }

    private val rapide = Runnable { if (actif) tic() }

    fun demarrer() {
        if (actif) return
        if (Build.VERSION.SDK_INT < 30) {
            DiagDetection.disponible = false
            DiagDetection.erreur = "Android 11+"
            return
        }
        DiagDetection.disponible = true
        actif = true
        fond.execute { chargerModele() }
        principal.post(boucle)
    }

    private var ferme = false

    /** Fin définitive (le service s'arrête) : libère aussi le fil de calcul. */
    fun fermer() {
        if (ferme) return
        ferme = true
        arreter()
        fond.shutdown()
    }

    fun arreter() {
        if (ferme && !actif) return
        actif = false
        principal.removeCallbacksAndMessages(null)
        cibles.clear()
        DiagDetection.floutees = 0
        try { if (vueAjoutee) wm.removeView(vue) } catch (e: Exception) { }
        vueAjoutee = false
        vue = null
        fond.execute {
            classifieur?.close()
            classifieur = null
            arbitre?.close()
            arbitre = null
            DiagDetection.modeleCharge = false
        }
    }

    /** Changement d'application au premier plan : les anciens flous n'ont plus de raison d'être. */
    fun surPaquet(paquet: String) {
        if (paquet == paquetAvant || paquet in IGNORES || paquet in claviers()) return
        paquetAvant = paquet
        cibles.clear()
        synchronized(surs) { surs.clear() }
        synchronized(soupcons) { soupcons.clear() }
        derniereSignature = null
        rafraichirVue()
    }

    /** Défilement : les flous d'image suivent leur image ; les zones de secours sont réévaluées. */
    fun surDefilement() {
        if (!actif) return
        cibles.removeAll { it.noeud == null }
        majCibles()
        derniereSignature = null
        rafraichirVue()
        principal.removeCallbacks(rapide)
        principal.postDelayed(rapide, 250L)
    }

    /** Contenu modifié (chargement, animation) : repositionne les flous, au plus tous les 120 ms. */
    fun surContenu() {
        if (!actif || cibles.isEmpty()) return
        val t = SystemClock.uptimeMillis()
        if (t - derniereMaj < 120L) return
        majCibles()
        rafraichirVue()
    }

    private var claviersCache: Set<String>? = null

    /** Paquets des claviers : leur apparition ne signifie pas qu'on a changé d'application. */
    private fun claviers(): Set<String> {
        claviersCache?.let { return it }
        val c = try {
            service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .inputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) { emptySet() }
        claviersCache = c
        return c
    }

    // ------------------------------------------------------------------ modèle

    private fun chargerModele() {
        if (classifieur != null && arbitre != null) return
        try {
            val octets = service.assets.open(MODELE).use { it.readBytes() }
            classifieur = Classifieur(octets)
            val octetsArbitre = service.assets.open(MODELE_ARBITRE).use { it.readBytes() }
            arbitre = Classifieur(octetsArbitre, COTE_ARBITRE, 0.5f, 0.5f)
            DiagDetection.modeleCharge = true
            DiagDetection.erreur = null
        } catch (e: Throwable) {
            DiagDetection.modeleCharge = false
            DiagDetection.erreur = "model: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ images suivies

    private fun estImage(classe: String): Boolean =
        classe.endsWith("ImageView") || classe == "android.widget.Image" ||
            classe.contains("Video") || classe.endsWith("TextureView") || classe.endsWith("SurfaceView")

    /** Les images (et vidéos) visibles de la fenêtre au premier plan, avec leur rectangle à l'écran. */
    private fun noeudsImages(): List<Pair<AccessibilityNodeInfo, Zone>> {
        val racine = try { service.rootInActiveWindow } catch (e: Exception) { null } ?: return emptyList()
        if (racine.packageName?.toString() == service.packageName) return emptyList()
        val res = ArrayList<Pair<AccessibilityNodeInfo, Zone>>()
        val pile = ArrayDeque<AccessibilityNodeInfo>()
        pile.addLast(racine)
        var vus = 0
        val r = Rect()
        while (pile.isNotEmpty() && vus < MAX_NOEUDS) {
            val n = pile.removeLast()
            vus++
            if (!n.isVisibleToUser) continue
            if (estImage(n.className?.toString() ?: "")) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0)
                    res.add(n to Zone(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
            }
            for (i in 0 until n.childCount) {
                val e = try { n.getChild(i) } catch (ex: Exception) { null } ?: continue
                pile.addLast(e)
            }
        }
        return res
    }

    /** Ce qui identifie le contenu d'une image (une vue recyclée dans une liste change d'empreinte). */
    private fun empreinte(n: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append(n.className).append('|').append(n.contentDescription).append('|').append(n.viewIdResourceName)
        var p: AccessibilityNodeInfo? = try { n.parent } catch (e: Exception) { null }
        var niveaux = 0
        while (p != null && niveaux < 8) {
            val info = p.collectionItemInfo
            if (info != null) {
                sb.append("|ligne ").append(info.rowIndex).append(" col ").append(info.columnIndex)
                break
            }
            p = try { p.parent } catch (e: Exception) { null }
            niveaux++
        }
        return sb.toString()
    }

    /** Met à jour la position et la visibilité de chaque image suivie. */
    private fun majCibles() {
        derniereMaj = SystemClock.uptimeMillis()
        if (cibles.isEmpty()) return
        val r = Rect()
        val it = cibles.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val n = c.noeud ?: continue
            val ok = try { n.refresh() } catch (e: Exception) { false }
            if (!ok) { it.remove(); continue }
            if (empreinte(n) != c.empreinte) {          // la vue montre maintenant autre chose
                it.remove()
                derniereSignature = null
                continue
            }
            n.getBoundsInScreen(r)
            c.visible = n.isVisibleToUser && r.width() > 0 && r.height() > 0
            if (c.visible) c.zone = Zone(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
    }

    /** Version floutée d'un morceau de la capture : réduite, adoucie ; l'agrandissement finit le flou. */
    private fun flou(capture: Bitmap, z: Zone): Bitmap? = try {
        val x = z.g.toInt().coerceIn(0, capture.width - 1)
        val y = z.h.toInt().coerceIn(0, capture.height - 1)
        val l = (z.d.toInt() - x).coerceAtMost(capture.width - x)
        val h = (z.b.toInt() - y).coerceAtMost(capture.height - y)
        if (l < 4 || h < 4) null
        else {
            val morceau = Bitmap.createBitmap(capture, x, y, l, h)
            val pl = maxOf(3, l / 18)
            val ph = maxOf(3, h / 18)
            val petit = Bitmap.createScaledBitmap(morceau, pl, ph, true)
            // pour l'écran entier, createBitmap renvoie la capture elle-même : ne pas la libérer
            if (petit !== morceau && morceau !== capture) morceau.recycle()
            val px = IntArray(pl * ph)
            petit.getPixels(px, 0, pl, 0, 0, pl, ph)
            petit.recycle()
            Bitmap.createBitmap(Pretraitement.flouBoite(px, pl, ph), pl, ph, Bitmap.Config.ARGB_8888)
        }
    } catch (e: Exception) { null }

    /** Une image (ou un morceau d'écran) à flouter, avec l'indice de son nœud (-1 : aucun). */
    private class Confirmee(val region: Zone, val image: Int, val detail: String)

    /** Images jugées sans problème récemment (empreinte -> instant), pour ne pas les réanalyser. */
    private val surs = LinkedHashMap<String, Long>()

    /**
     * Clé d'une image pour la mémoire des images sûres : empreinte + position + taille. Une
     * image sans identité propre (cas des pages web : pas de description ni d'identifiant)
     * n'a pas de clé, car toutes les miniatures d'une page se ressembleraient.
     */
    private fun cleSure(n: AccessibilityNodeInfo, z: Zone): String? {
        if (n.contentDescription.isNullOrBlank() && n.viewIdResourceName.isNullOrBlank()) return null
        return empreinte(n) + "|" + z.g.toInt() + "," + z.h.toInt() + "|" + z.l.toInt() + "x" + z.ht.toInt()
    }

    /** Position et taille : identifie une image sur l'écran actuel (pour analyser toutes les images, par lots). */
    private fun clePosition(z: Zone) = "" + z.g.toInt() + "," + z.h.toInt() + "|" + z.l.toInt() + "x" + z.ht.toInt()

    /** Images déjà jugées sûres sur l'écran actuel ; vidé dès que l'écran change. */
    private val vusEcran = HashSet<String>()

    /** Il reste des images à analyser sur cet écran (plus de MAX_CLASSIF) ou un soupçon à confirmer. */
    @Volatile private var aContinuer = false

    private fun estVideo(n: AccessibilityNodeInfo): Boolean {
        val c = n.className?.toString() ?: return false
        return c.contains("Video") || c.endsWith("TextureView") || c.endsWith("SurfaceView")
    }

    /**
     * Découpe une région de la capture et la ramène à [c] x [c] pixels pour un modèle.
     * [centre] : seulement le carré central de la région (deuxième avis, sans déformation).
     */
    private fun pixelsRegion(capture: Bitmap, z: Zone, centre: Boolean = false, c: Int = Classement.COTE): IntArray? {
        var x = z.g.toInt().coerceIn(0, capture.width - 1)
        var y = z.h.toInt().coerceIn(0, capture.height - 1)
        var l = (z.d.toInt() - x).coerceAtMost(capture.width - x)
        var h = (z.b.toInt() - y).coerceAtMost(capture.height - y)
        if (centre) {
            val cote = (minOf(l, h) * 0.9f).toInt()
            x += (l - cote) / 2
            y += (h - cote) / 2
            l = cote
            h = cote
        }
        if (l < 16 || h < 16) return null
        val morceau = Bitmap.createBitmap(capture, x, y, l, h)
        val reduit = Bitmap.createScaledBitmap(morceau, c, c, true)
        // pour l'écran entier, createBitmap renvoie la capture elle-même : ne pas la libérer
        if (morceau !== reduit && morceau !== capture) morceau.recycle()
        val px = IntArray(c * c)
        reduit.getPixels(px, 0, c, 0, 0, c, c)
        reduit.recycle()
        return px
    }

    /** Floute l'image entière de chaque confirmation ; sans nœud, la région elle-même. */
    private fun placer(confirmees: List<Confirmee>, images: List<Pair<AccessibilityNodeInfo, Zone>>, capture: Bitmap) {
        for (c in confirmees) {
            if (cibles.any { it.visible && it.zone.contientCentre(c.region) && it.zone.aire >= c.region.aire }) continue
            if (c.image in images.indices) {
                val (noeud, zone) = images[c.image]
                val existante = cibles.firstOrNull { it.noeud == noeud }
                if (existante != null) { existante.visible = true; existante.zone = zone; continue }
                cibles.add(Cible(noeud, zone, flou(capture, zone), empreinte(noeud), etiquette = if (essai) c.detail else null))
            } else {
                cibles.add(Cible(null, c.region, flou(capture, c.region), "", etiquette = if (essai) c.detail else null))
            }
            while (cibles.size > MAX_CIBLES) cibles.removeAt(0)
        }
    }

    // ------------------------------------------------------------------ affichage

    private fun rafraichirVue() {
        val m = cibles.filter { it.visible }.map { Masque(RectF(it.zone.g, it.zone.h, it.zone.d, it.zone.b), it.flou, true, it.etiquette) }
        DiagDetection.floutees = m.size
        if (m.isEmpty()) {
            vue?.masques = emptyList()
            return
        }
        assurerVue().masques = m
    }

    private fun assurerVue(): MasqueVue {
        vue?.let { return it }
        val v = MasqueVue(service)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, p)
        vue = v
        vueAjoutee = true
        return v
    }

    // ------------------------------------------------------------------ boucle

    private fun ecranUtilisable(): Boolean =
        ecran.isInteractive && !verrouEcran.isKeyguardLocked && paquetAvant != service.packageName

    private fun tic() {
        majCibles()
        // Un flou d'écran entier cache ce qu'il faudrait revoir (la capture le contient) : il est
        // retiré au bout de DUREE_ECRAN_MS, l'écran est aussitôt réanalysé, et le flou revient en
        // un seul jugement si la scène est toujours pornographique.
        val t = SystemClock.uptimeMillis()
        if (cibles.removeAll { it.noeud == null && t - it.creee > DUREE_ECRAN_MS }) {
            dernierEcranRetire = t
            derniereSignature = null
            rafraichirVue()
            principal.postDelayed({ if (actif && !enCours && classifieur != null && ecranUtilisable()) capturer() }, 120L)
            return
        }
        rafraichirVue()
        if (enCours || classifieur == null || !ecranUtilisable()) return
        if (cibles.any { it.noeud == null && it.visible }) return     // écran déjà flouté : rien à voir dessous
        capturer()
    }

    // ------------------------------------------------------------------ capture

    private fun capturer() {
        if (Build.VERSION.SDK_INT < 30 || enCours) return
        enCours = true
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        val tampon = r.hardwareBuffer
                        val image = try { Bitmap.wrapHardwareBuffer(tampon, r.colorSpace) } catch (e: Exception) { null }
                        if (image == null) {
                            tampon.close()
                            enCours = false
                            return
                        }
                        fond.execute { traiter(image, tampon) }
                    }

                    override fun onFailure(code: Int) {
                        enCours = false
                        DiagDetection.dernierDetail = code.toString()
                        DiagDetection.dernier = Analyse.REFUSEE
                    }
                },
            )
        } catch (e: Exception) {
            enCours = false
            DiagDetection.erreur = "screenshot: ${e.message}"
        }
    }

    private fun traiter(image: Bitmap, tampon: android.hardware.HardwareBuffer) {
        var capture: Bitmap? = null
        try {
            val m = classifieur ?: return
            val a = arbitre ?: return
            val ecranL = image.width
            val ecranH = image.height
            val cap = image.copy(Bitmap.Config.ARGB_8888, false) ?: return
            capture = cap
            // petite copie : écran noir ? écran inchangé ?
            val sl = 96
            val sh = maxOf(1, sl * ecranH / maxOf(1, ecranL))
            val reduit = Bitmap.createScaledBitmap(cap, sl, sh, true)
            val pixels = IntArray(sl * sh)
            reduit.getPixels(pixels, 0, sl, 0, 0, sl, sh)
            if (reduit !== cap) reduit.recycle()
            if (Pretraitement.luminanceMoyenne(pixels, sl, sh) < 3f) {      // lecteur protégé ou écran éteint
                DiagDetection.ecransProteges++
                DiagDetection.dernier = Analyse.NOIR
                return
            }
            val signature = Pretraitement.signature(pixels, sl, sh)
            val change = Pretraitement.difference(derniereSignature, signature) >= SEUIL_CHANGEMENT
            derniereSignature = signature
            if (change) vusEcran.clear()
            if (!change && !aContinuer) {
                DiagDetection.dernier = Analyse.INCHANGE
                return
            }

            val sens = etat.sensibilite
            val enfant = etat.modeEnfant
            val lc = ecranL.toFloat()
            val hc = ecranH.toFloat()
            val images = noeudsImages()
            val maintenant = SystemClock.uptimeMillis()
            synchronized(surs) { surs.entries.removeAll { maintenant - it.value > DUREE_SUR_MS } }

            // ce qui sera analysé : chaque image déclarée, sinon l'écran entier
            val aAnalyser = ArrayList<Triple<Zone, Int, String?>>()   // zone, indice d'image, clé « sûre »
            var resteImages = false
            val candidats = Classement.candidats(images.map { it.second }, lc, hc)
            // une grande image déclarée couvre l'écran ; sinon (icônes seulement), la vidéo n'est pas déclarée
            val grande = candidats.any { images[it].second.aire >= 0.25f * lc * hc }
            if (candidats.isNotEmpty()) {
                for (i in candidats) {
                    val (n, z) = images[i]
                    if (cibles.any { it.noeud == n && it.visible }) continue      // déjà floutée
                    val cle = if (estVideo(n)) null else cleSure(n, z)
                    if (cle != null && synchronized(surs) { cle in surs }) continue
                    if (!estVideo(n) && clePosition(z) in vusEcran) continue        // déjà jugée sûre sur cet écran
                    if (aAnalyser.size >= MAX_CLASSIF) { resteImages = true; break }
                    aAnalyser.add(Triple(z, i, cle))
                }
            }
            if (!grande) {
                // l'application ne déclare pas ses images (vidéos de Facebook, Shorts, Reels…) :
                // l'écran est jugé en entier, comme les modèles ont appris à le faire. Des morceaux
                // découpés montreraient des gros plans de peau, que les modèles prennent pour du porno.
                aAnalyser.add(Triple(Zone(0f, 0f, lc, hc), -1, null))
            }

            val debut = System.nanoTime()
            val confirmees = ArrayList<Confirmee>()
            val details = ArrayList<String>()
            var relancer = false
            for ((z, i, cle) in aAnalyser) {
                if (i < 0 && confirmees.isNotEmpty()) continue   // une image précise est déjà floutée
                val px = pixelsRegion(cap, z) ?: continue
                val p = m.classer(px)
                val tuile = false
                // deuxième avis, d'un autre modèle : les deux doivent être d'accord
                var nsfw = 0f
                val confirme = Classement.suspect(p, sens, enfant) &&
                    (pixelsRegion(cap, z, c = a.cote)?.let {
                        nsfw = probabilitePremiere(a.classer(it))
                        Classement.aFlouter(p, nsfw, sens, enfant, tuile)
                    } ?: false)
                if (confirme) {
                    // confirmation dans le temps : il faut deux jugements de suite (≤ 5 s)
                    val cleSoupcon = if (i >= 0) empreinte(images[i].first) + "|" + z.l.toInt() + "x" + z.ht.toInt()
                    else "ecran"
                    val retour = i < 0 && maintenant - dernierEcranRetire < DUREE_ECRAN_MS + 2_000L
                    val deja = retour || synchronized(soupcons) {
                        soupcons.entries.removeAll { maintenant - it.value > 5_000L }
                        val v = soupcons.remove(cleSoupcon) != null
                        if (!v) soupcons[cleSoupcon] = maintenant
                        v
                    }
                    if (deja) {
                        val d = (if (i >= 0) "image" else "écran entier") + " · " + Classement.resume(p) +
                            " · NSFW ${(nsfw * 100).toInt()}%"
                        confirmees.add(Confirmee(z, i, d))
                        details.add(d)
                    } else {
                        relancer = true
                    }
                } else {
                    if (i >= 0 && !estVideo(images[i].first)) vusEcran.add(clePosition(z))
                    if (cle != null) synchronized(surs) {
                        surs[cle] = maintenant
                        while (surs.size > MAX_SURS) surs.remove(surs.keys.first())
                    }
                }
            }
            // un soupçon attend sa confirmation : la prochaine analyse a lieu même si l'écran n'a pas changé
            // la prochaine analyse a lieu même si l'écran n'a pas changé : soupçon à confirmer,
            // ou images pas encore vues (plus de MAX_CLASSIF sur l'écran)
            aContinuer = relancer || resteImages
            DiagDetection.analyses++
            DiagDetection.dernierMs = (System.nanoTime() - debut) / 1_000_000L
            DiagDetection.dernierDetail = details.joinToString()
            DiagDetection.dernier = if (confirmees.isEmpty()) Analyse.RIEN else Analyse.MASQUE
            if (confirmees.isNotEmpty()) {
                capture = null                                  // l'image passe au fil principal, qui la libérera
                principal.post {
                    try {
                        if (actif) {
                            placer(confirmees, images, cap)
                            rafraichirVue()
                            surFlou(paquetAvant, confirmees.size)
                        }
                    } finally {
                        cap.recycle()
                    }
                }
            }
        } catch (e: Throwable) {
            DiagDetection.erreur = "analysis: ${e.message}"
        } finally {
            capture?.recycle()
            try { tampon.close() } catch (e: Exception) { }
            enCours = false
        }
    }

    companion object {
        const val SEUIL_CHANGEMENT = 0.004f
        const val MAX_NOEUDS = 2500
        const val MAX_CIBLES = 40
        const val MAX_CLASSIF = 6
        /** Durée d'un flou d'écran entier avant nouvelle vérification. */
        const val DUREE_ECRAN_MS = 4_000L
        const val MAX_SURS = 300
        const val DUREE_SUR_MS = 10_000L
        const val MODELE = "nsfwjs_mobilenet_v2.onnx"
        const val MODELE_ARBITRE = "marqo_nsfw_384.onnx"
        const val COTE_ARBITRE = 384
        private val IGNORES = setOf("com.android.systemui")
    }
}
