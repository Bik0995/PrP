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
 * Détection d'images à l'écran : capture l'écran avec le service d'accessibilité, repère la
 * nudité avec le modèle local, revérifie chaque soupçon de près, puis **floute l'image entière**
 * (ou la vidéo) qui la contient. Le flou suit l'image quand on fait défiler ; il disparaît
 * quand l'image sort de l'écran et revient dès qu'elle réapparaît. Si aucune image n'est
 * reconnue autour (vue dessinée par l'application), une large zone autour est floutée,
 * jusqu'au prochain défilement ou changement d'application.
 * Rien n'est enregistré ni envoyé : chaque capture est analysée en mémoire puis jetée.
 * Nécessite Android 11 minimum (capture d'écran par accessibilité).
 */
class Detection(private val service: AccessibilityService) {
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
    )

    private val cibles = ArrayList<Cible>()
    private var vue: MasqueVue? = null
    private var vueAjoutee = false
    private var detecteur: Detecteur? = null
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
            detecteur?.close()
            detecteur = null
            DiagDetection.modeleCharge = false
        }
    }

    /** Changement d'application au premier plan : les anciens flous n'ont plus de raison d'être. */
    fun surPaquet(paquet: String) {
        if (paquet == paquetAvant || paquet in IGNORES || paquet in claviers()) return
        paquetAvant = paquet
        cibles.clear()
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
        if (detecteur != null) return
        try {
            val octets = service.assets.open("nudenet_320n.onnx").use { it.readBytes() }
            detecteur = Detecteur(octets)
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
            if (petit !== morceau) morceau.recycle()
            val px = IntArray(pl * ph)
            petit.getPixels(px, 0, pl, 0, 0, pl, ph)
            petit.recycle()
            Bitmap.createBitmap(Pretraitement.flouBoite(px, pl, ph), pl, ph, Bitmap.Config.ARGB_8888)
        }
    } catch (e: Exception) { null }

    /**
     * Revoit chaque suspect de près : la région analysée est l'image entière qui le contient
     * (ou, à défaut, la zone agrandie), passée seule au modèle, donc en bien plus grand.
     * Ne garde que les détections confirmées, avec leur région à flouter et l'indice de leur image.
     */
    private class Confirmee(val boite: Boite, val region: Zone, val image: Int)

    private fun verifier(
        d: Detecteur, capture: Bitmap, suspects: List<Boite>,
        images: List<Pair<AccessibilityNodeInfo, Zone>>, sens: Int, enfant: Boolean,
    ): Pair<List<Confirmee>, Long> {
        val lc = capture.width.toFloat()
        val hc = capture.height.toFloat()
        val cadres = images.map { it.second }
        val regions = LinkedHashMap<String, Pair<Zone, Int>>()
        for (b in suspects) {
            val zb = Zone(b.x, b.y, b.droite, b.bas)
            val i = Cadres.choisir(zb, cadres, lc * hc)
            val z = if (i >= 0) cadres[i] else {
                val mx = maxOf(b.l * 0.8f, 160f)
                val my = maxOf(b.h * 0.8f, 160f)
                Zone(b.x - mx, b.y - my, b.droite + mx, b.bas + my)
            }
            val zc = Zone(z.g.coerceIn(0f, lc), z.h.coerceIn(0f, hc), z.d.coerceIn(0f, lc), z.b.coerceIn(0f, hc))
            regions.getOrPut("${zc.g.toInt()},${zc.h.toInt()},${zc.d.toInt()},${zc.b.toInt()}") { zc to i }
        }
        val res = ArrayList<Confirmee>()
        var ms = 0L
        for ((z, i) in regions.values.take(MAX_VERIFS)) {
            val x = z.g.toInt()
            val y = z.h.toInt()
            val rl = (z.d - z.g).toInt()
            val rh = (z.b - z.h).toInt()
            if (rl < 16 || rh < 16) continue
            val (l, h) = Yolo.dimensionsEntree(rl, rh)
            val morceau = Bitmap.createBitmap(capture, x, y, rl, rh)
            val reduit = Bitmap.createScaledBitmap(morceau, l, h, true)
            if (morceau !== reduit) morceau.recycle()
            val px = IntArray(l * h)
            reduit.getPixels(px, 0, l, 0, 0, l, h)
            reduit.recycle()
            val r = d.detecter(px, l, h, rl.toFloat(), rh.toFloat())
            ms += r.msInference
            for (b in r.boites) {
                if (!Classes.visee(b.classe, enfant) || b.score < Classes.seuil(b.classe, sens, enfant)) continue
                res.add(Confirmee(Boite(b.x + x, b.y + y, b.l, b.h, b.classe, b.score), z, i))
            }
        }
        return res to ms
    }

    /** Floute l'image entière qui contient chaque détection confirmée ; sinon, sa large zone. */
    private fun placer(confirmees: List<Confirmee>, images: List<Pair<AccessibilityNodeInfo, Zone>>, capture: Bitmap) {
        for (c in confirmees) {
            val b = c.boite
            val zb = Zone(b.x, b.y, b.droite, b.bas)
            if (cibles.any { it.visible && Cadres.contientCentre(it.zone, zb) }) continue
            if (c.image in images.indices) {
                val (noeud, zone) = images[c.image]
                val existante = cibles.firstOrNull { it.noeud == noeud }
                if (existante != null) { existante.visible = true; existante.zone = zone; continue }
                cibles.add(Cible(noeud, zone, flou(capture, zone), empreinte(noeud)))
            } else {
                cibles.add(Cible(null, c.region, flou(capture, c.region), ""))
            }
            while (cibles.size > MAX_CIBLES) cibles.removeAt(0)
        }
    }

    // ------------------------------------------------------------------ affichage

    private fun rafraichirVue() {
        val m = cibles.filter { it.visible }.map { Masque(RectF(it.zone.g, it.zone.h, it.zone.d, it.zone.b), it.flou, true) }
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
        rafraichirVue()
        if (enCours || detecteur == null || !ecranUtilisable()) return
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
            val d = detecteur ?: return
            val ecranL = image.width
            val ecranH = image.height
            val (l, h) = Yolo.dimensionsEntree(ecranL, ecranH)
            val cap = image.copy(Bitmap.Config.ARGB_8888, false) ?: return
            capture = cap
            val reduit = Bitmap.createScaledBitmap(cap, l, h, true)
            val pixels = IntArray(l * h)
            reduit.getPixels(pixels, 0, l, 0, 0, l, h)
            if (reduit !== cap) reduit.recycle()

            if (Pretraitement.luminanceMoyenne(pixels, l, h) < 3f) {       // écran noir : lecteur protégé ou écran éteint
                DiagDetection.ecransProteges++
                DiagDetection.dernier = Analyse.NOIR
                return
            }
            val signature = Pretraitement.signature(pixels, l, h)
            val change = Pretraitement.difference(derniereSignature, signature) >= SEUIL_CHANGEMENT
            derniereSignature = signature
            if (!change) {
                DiagDetection.dernier = Analyse.INCHANGE
                return
            }
            val res = d.detecter(pixels, l, h, ecranL.toFloat(), ecranH.toFloat())
            DiagDetection.analyses++
            var ms = res.msInference
            val sens = etat.sensibilite
            val enfant = etat.modeEnfant
            val suspects = res.boites.filter {
                Classes.visee(it.classe, enfant) && it.score >= Classes.seuilSuspect(it.classe, sens, enfant)
            }
            var confirmees = emptyList<Confirmee>()
            var images = emptyList<Pair<AccessibilityNodeInfo, Zone>>()
            if (suspects.isNotEmpty()) {
                images = noeudsImages()
                val (c, msVerif) = verifier(d, cap, suspects, images, sens, enfant)
                confirmees = c
                ms += msVerif
            }
            DiagDetection.dernierMs = ms
            DiagDetection.dernierDetail = confirmees.joinToString { "${Classes.NOMS[it.boite.classe]} ${(it.boite.score * 100).toInt()}%" }
            DiagDetection.dernier = if (confirmees.isEmpty()) Analyse.RIEN else Analyse.MASQUE
            if (confirmees.isNotEmpty()) {
                val im = images
                capture = null                                  // l'image passe au fil principal, qui la libérera
                principal.post {
                    try {
                        if (actif) {
                            placer(confirmees, im, cap)
                            rafraichirVue()
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
        const val MAX_VERIFS = 4
        private val IGNORES = setOf("com.android.systemui")
    }
}
