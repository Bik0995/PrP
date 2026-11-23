package org.prp.android.detection

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
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
    /** null = pas encore mesuré ; true = le masque apparaît dans les captures ; false = non. */
    @Volatile var masqueDansCapture: Boolean? = null
    @Volatile var analyses = 0
    @Volatile var ecransProteges = 0
}

/**
 * Détection d'images à l'écran : capture l'écran avec le service d'accessibilité, cherche les
 * parties exposées avec le modèle local, et pose un masque opaque sur les zones concernées.
 * Rien n'est enregistré ni envoyé : chaque capture est analysée en mémoire puis jetée.
 * Nécessite Android 11 minimum (capture d'écran par accessibilité).
 */
class Detection(private val service: AccessibilityService) {
    private val principal = Handler(Looper.getMainLooper())
    private val fond = Executors.newSingleThreadExecutor()
    private val controle = ControleurMasque()
    private val etat = Etat(service)
    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val ecran: PowerManager = service.getSystemService(PowerManager::class.java)
    private val verrouEcran: KeyguardManager = service.getSystemService(KeyguardManager::class.java)

    private var vue: MasqueVue? = null
    private var vueAjoutee = false
    private var detecteur: Detecteur? = null
    private var actif = false
    private var enCours = false
    private var derniereSignature: IntArray? = null
    private var dernierProbe = 0L
    private var paquetAvant = ""

    private val boucle = object : Runnable {
        override fun run() {
            if (!actif) return
            tic()
            val delai = if (controle.aDesZones(SystemClock.uptimeMillis())) 700L else 1000L
            principal.postDelayed(this, delai)
        }
    }

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
        controle.effacer()
        try { if (vueAjoutee) wm.removeView(vue) } catch (e: Exception) { }
        vueAjoutee = false
        vue = null
        fond.execute {
            detecteur?.close()
            detecteur = null
            DiagDetection.modeleCharge = false
        }
    }

    /** Changement d'application au premier plan : l'ancien masque n'a plus de raison d'être. */
    fun surPaquet(paquet: String) {
        if (paquet == paquetAvant || paquet in IGNORES || paquet in claviers()) return
        paquetAvant = paquet
        controle.effacer()
        derniereSignature = null
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

    // ------------------------------------------------------------------ boucle

    private fun ecranUtilisable(): Boolean =
        ecran.isInteractive && !verrouEcran.isKeyguardLocked && paquetAvant != service.packageName

    private fun tic() {
        val maintenant = SystemClock.uptimeMillis()
        rafraichirVue()
        if (enCours || detecteur == null || !ecranUtilisable()) return
        val masque = controle.aDesZones(maintenant)
        val mode = DiagDetection.masqueDansCapture
        controle.tenueMs = if (mode == true) TENUE_PROBE_MS else TENUE_MS
        when {
            !masque || mode == false -> capturer(false)
            mode == null -> capturer(false)                      // calibrage : capture avec le masque affiché
            maintenant - dernierProbe >= PROBE_MS -> {           // le masque s'efface un instant pour revoir l'écran
                dernierProbe = maintenant
                vue?.cache = true
                principal.postDelayed({ capturer(true) }, 90L)
            }
            // sinon : le masque tient, rien à faire
        }
    }

    private fun rafraichirVue() {
        val zones = controle.actives(SystemClock.uptimeMillis())
        if (zones.isEmpty()) {
            vue?.zones = emptyList()
            return
        }
        val v = assurerVue()
        v.zones = zones
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

    // ------------------------------------------------------------------ capture

    private fun capturer(apresProbe: Boolean) {
        if (Build.VERSION.SDK_INT < 30 || enCours) {
            vue?.cache = false
            return
        }
        enCours = true
        val masqueVisibleALaCapture = vue != null && !apresProbe && controle.aDesZones(SystemClock.uptimeMillis())
        val zonesALaCapture = controle.actives(SystemClock.uptimeMillis())
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        vue?.cache = false                       // le masque revient tout de suite
                        val tampon = r.hardwareBuffer
                        val image = try { Bitmap.wrapHardwareBuffer(tampon, r.colorSpace) } catch (e: Exception) { null }
                        if (image == null) {
                            tampon.close()
                            enCours = false
                            return
                        }
                        fond.execute { traiter(image, tampon, masqueVisibleALaCapture, zonesALaCapture) }
                    }

                    override fun onFailure(code: Int) {
                        vue?.cache = false
                        enCours = false
                        DiagDetection.dernierDetail = code.toString()
                        DiagDetection.dernier = Analyse.REFUSEE
                    }
                },
            )
        } catch (e: Exception) {
            vue?.cache = false
            enCours = false
            DiagDetection.erreur = "screenshot: ${e.message}"
        }
    }

    private fun traiter(
        image: Bitmap, tampon: android.hardware.HardwareBuffer,
        masqueVisible: Boolean, zonesVisibles: List<Zone>,
    ) {
        try {
            val d = detecteur ?: return
            val ecranL = image.width
            val ecranH = image.height
            val (l, h) = Yolo.dimensionsEntree(ecranL, ecranH)
            val logiciel = image.copy(Bitmap.Config.ARGB_8888, false) ?: return
            val reduit = Bitmap.createScaledBitmap(logiciel, l, h, true)
            logiciel.recycle()
            val pixels = IntArray(l * h)
            reduit.getPixels(pixels, 0, l, 0, 0, l, h)
            reduit.recycle()

            if (masqueVisible && DiagDetection.masqueDansCapture == null) calibrer(pixels, l, h, zonesVisibles)

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
            DiagDetection.dernierMs = res.msInference
            val sens = etat.sensibilite
            val visees = res.boites.filter { Classes.visee(it.classe) && it.score >= Classes.seuil(it.classe, sens) }
            DiagDetection.dernierDetail = visees.joinToString { "${Classes.NOMS[it.classe]} ${(it.score * 100).toInt()}%" }
            DiagDetection.dernier = if (visees.isEmpty()) Analyse.RIEN else Analyse.MASQUE
            principal.post {
                if (!actif) return@post
                controle.mettreAJour(res.boites, ecranL.toFloat(), ecranH.toFloat(), sens, SystemClock.uptimeMillis())
                rafraichirVue()
            }
        } catch (e: Throwable) {
            DiagDetection.erreur = "analysis: ${e.message}"
        } finally {
            try { tampon.close() } catch (e: Exception) { }
            enCours = false
        }
    }

    /** Mesure une fois si le masque lui-même apparaît dans les captures d'écran de cet appareil. */
    private fun calibrer(pixels: IntArray, l: Int, h: Int, zones: List<Zone>) {
        val z = zones.firstOrNull() ?: return
        var oui = 0
        var total = 0
        for (i in 1..3) for (j in 1..3) {
            val x = ((z.g + (z.d - z.g) * i / 4f) * l).toInt().coerceIn(0, l - 1)
            val y = ((z.h + (z.b - z.h) * j / 4f) * h).toInt().coerceIn(0, h - 1)
            total++
            if (Pretraitement.couleurProche(pixels[y * l + x], MasqueVue.R, MasqueVue.G, MasqueVue.B, 12)) oui++
        }
        DiagDetection.masqueDansCapture = oui >= 7 && total == 9
    }

    companion object {
        const val TENUE_MS = 2500L
        const val TENUE_PROBE_MS = 3800L
        const val PROBE_MS = 2000L
        const val SEUIL_CHANGEMENT = 0.004f
        private val IGNORES = setOf("com.android.systemui")
    }
}
