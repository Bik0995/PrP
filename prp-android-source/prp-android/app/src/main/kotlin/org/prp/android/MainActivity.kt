package org.prp.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.prp.android.Apparence.HEURE
import org.prp.android.Apparence.JOUR
import org.prp.android.Apparence.MINUTE
import org.prp.android.Apparence.MOIS
import org.prp.android.detection.Analyse
import org.prp.android.detection.DiagDetection
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** Écran principal de PrP : état, protections, détection d'images, verrou, réglages. */
class MainActivity : Activity() {

    private lateinit var racine: LinearLayout
    private lateinit var v: Vues
    private var occupe = false

    // choix en cours (gardés tant que l'écran est ouvert)
    private var duree = 30 * JOUR
    private var delai = 48 * HEURE
    private var prolongation = 30 * JOUR

    private val minuterie = Handler(Looper.getMainLooper())
    private val rafraichir = object : Runnable {
        override fun run() {
            if (!occupe) dessiner()
            minuterie.postDelayed(this, 30_000L)
        }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Apparence.envelopper(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        v = Vues(this, Apparence.palette(this))
        racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(v.dp(16), v.dp(12), v.dp(16), v.dp(32))
        }
        val defile = ScrollView(this).apply {
            setBackgroundColor(v.pal.fond)
            isFillViewport = true
            addView(racine)
        }
        setContentView(defile)
        habillerBarres()
    }

    override fun onResume() {
        super.onResume()
        Gardien.programmer(this)
        minuterie.post(rafraichir)
    }

    override fun onPause() {
        super.onPause()
        minuterie.removeCallbacks(rafraichir)
    }

    @Suppress("DEPRECATION")
    private fun habillerBarres() {
        window.statusBarColor = v.pal.fond
        window.navigationBarColor = v.pal.fond
        if (!Apparence.estSombre(this)) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    // ---------------------------------------------------------------- écran

    private fun dessiner() {
        racine.removeAllViews()
        val e = Etat(this)
        val diag = Protections.diagnostic(this)

        enTete()
        if (Protections.estEssai(this)) banniereEssai()
        carteEtat(e, diag)
        carteProtections(e, diag)

        if (!e.verrouActif && !Gardien.gardeActive(this)) carteGarde()
        val proprio = Protections.estProprietaire(this)
        if (!e.verrouActif && !proprio) carteProprietaire()

        carteDetection(e)

        if (e.verrouActif) {
            carteVerrouActif(e)
            carteProlonger()
        } else if (proprio) {
            carteVerrouiller()
        }
        if (!e.verrouActif) carteRetirer()
    }

    private fun enTete() {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, v.dp(8), 0, v.dp(4))
        }
        ligne.addView(v.icone(R.drawable.ic_prp, null, 44))
        val titres = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(v.dp(12), 0, 0, 0)
        }
        v.texte(titres, getString(R.string.app_name), 26f, v.pal.texte, gras = true)
        v.texte(titres, getString(R.string.sous_titre), 13f, v.pal.texteDoux)
        ligne.addView(titres, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ligne.addView(v.boutonIcone(R.drawable.ic_refresh, v.pal.texteDoux, getString(R.string.actualiser)) {
            dessiner()
        })
        ligne.addView(v.boutonIcone(R.drawable.ic_settings, v.pal.texteDoux, getString(R.string.reglages)) {
            fenetreReglages()
        })
        racine.addView(ligne)
    }

    private fun banniereEssai() {
        val c = v.carte(racine, v.pal.fondOrange, null)
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ligne.addView(v.icone(R.drawable.ic_warning, v.pal.orange, 22))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(v.dp(12), 0, 0, 0)
        }
        v.texte(col, getString(R.string.essai_titre), 15f, v.pal.orange, gras = true)
        v.texte(col, getString(R.string.essai_texte), 13f, v.pal.texte)
        ligne.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(ligne)
    }

    /** Grande carte d'état en haut : verrouillé, prêt, ou à compléter. */
    private fun carteEtat(e: Etat, diag: List<Pair<Protections.Couche, Boolean>>) {
        val base = diag.filter {
            it.first == Protections.Couche.PROPRIETAIRE || it.first == Protections.Couche.GARDE ||
                it.first == Protections.Couche.REACTIVATION
        }.all { it.second }
        val (iconeId, couleur, fond) = when {
            e.verrouActif -> Triple(R.drawable.ic_lock, v.pal.primaire, v.pal.fondPrimaire)
            base -> Triple(R.drawable.ic_verified_user, v.pal.vert, v.pal.fondVert)
            else -> Triple(R.drawable.ic_warning, v.pal.orange, v.pal.fondOrange)
        }
        val c = v.carte(racine, fond, null)
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ligne.addView(v.icone(iconeId, couleur, 44))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(v.dp(14), 0, 0, 0)
        }
        if (e.verrouActif) {
            val fin = Drand.dateRondeMs(e.rondeCible)
            v.texte(col, getString(R.string.etat_verrouille), 21f, couleur, gras = true)
            v.texte(col, getString(R.string.etat_jusqua, dateLongue(fin)), 15f, v.pal.texte)
            val reste = (fin - System.currentTimeMillis()) / 1000
            v.texte(col,
                if (reste > 0) getString(R.string.etat_reste, Apparence.restant(this, reste))
                else getString(R.string.etat_echeance),
                14f, v.pal.texteDoux)
        } else if (base) {
            v.texte(col, getString(R.string.etat_pret), 21f, couleur, gras = true)
            v.texte(col, getString(R.string.etat_pret_texte), 14f, v.pal.texte)
        } else {
            v.texte(col, getString(R.string.etat_incomplet), 21f, couleur, gras = true)
            v.texte(col, getString(R.string.etat_incomplet_texte), 14f, v.pal.texte)
        }
        ligne.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(ligne)
    }

    private fun carteProtections(e: Etat, diag: List<Pair<Protections.Couche, Boolean>>) {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_verified_user, getString(R.string.protections))
        for ((couche, ok) in diag) {
            v.ligneEtat(c, ok, getString(couche.nom)) { fenetreCouche(couche, ok) }
        }
        if (e.dnsDegrade) {
            v.espace(c, 6)
            v.note(c, getString(R.string.dns_degrade))
        }
    }

    private fun carteGarde() {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_verified_user, getString(R.string.etape_garde))
        v.note(c, getString(R.string.aide_garde))
        boutonGarde(c)
    }

    private fun boutonGarde(parent: LinearLayout) {
        if (Gardien.peutReactiver(this)) {
            v.bouton(parent, getString(R.string.activer_garde), R.drawable.ic_verified_user) {
                Gardien.reactiverGarde(this)
                racine.postDelayed({ dessiner() }, 800)
            }
        } else {
            v.note(parent, getString(R.string.garde_manuelle))
            v.bouton(parent, getString(R.string.ouvrir_accessibilite), R.drawable.ic_settings) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun carteProprietaire() {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_lock, getString(R.string.proprio_titre), v.pal.orange)
        v.note(c, getString(R.string.aide_proprietaire))
        v.espace(c, 4)
        v.note(c, getString(R.string.proprio_attente))
    }

    private fun carteDetection(e: Etat) {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_visibility_off, getString(R.string.detection_titre)) {
            fenetre(getString(R.string.detection_titre), getString(R.string.detection_texte), R.drawable.ic_visibility_off)
        }
        if (Build.VERSION.SDK_INT < 30) {
            v.note(c, getString(R.string.detection_android11))
            return
        }

        // interrupteur
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, v.dp(4), 0, v.dp(4))
        }
        ligne.addView(TextView(this).apply {
            text = getString(R.string.detection_active)
            textSize = 16f
            setTextColor(v.pal.texte)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ligne.addView(Switch(this).apply {
            isChecked = e.detectionActive
            isEnabled = !e.verrouActif
            setOnCheckedChangeListener { _, actif ->
                e.detectionActive = actif
                GardeService.instance?.majDetection()
                racine.post { dessiner() }
            }
        })
        c.addView(ligne)

        // mode enfant (pendant le verrou : on peut l'allumer, pas l'éteindre)
        val ligneEnfant = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, v.dp(4), 0, 0)
        }
        ligneEnfant.addView(TextView(this).apply {
            text = getString(R.string.mode_enfant)
            textSize = 16f
            setTextColor(v.pal.texte)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ligneEnfant.addView(Switch(this).apply {
            isChecked = e.modeEnfant
            isEnabled = !(e.verrouActif && e.modeEnfant)
            setOnCheckedChangeListener { _, oui ->
                e.modeEnfant = oui
                racine.post { dessiner() }
            }
        })
        c.addView(ligneEnfant)
        v.note(c, getString(R.string.mode_enfant_texte))
        v.espace(c, 8)

        // sensibilité
        v.texte(c, getString(R.string.sensibilite), 14f, v.pal.texteDoux, gras = true)
        val noms = listOf(getString(R.string.sens_stricte), getString(R.string.sens_normale), getString(R.string.sens_souple))
        val actuelle = e.sensibilite
        v.puces(c, noms, actuelle, parLigne = 3, actif = { !e.verrouActif || it <= actuelle }) {
            e.sensibilite = it
            dessiner()
        }
        if (e.verrouActif) {
            v.espace(c, 4)
            v.note(c, getString(R.string.detection_verrou))
        }

        if (e.detectionActive) {
            v.espace(c, 6)
            v.note(c, getString(R.string.diag_derniere, texteAnalyse()))
            DiagDetection.erreur?.let { v.texte(c, getString(R.string.diag_erreur, it), 14f, v.pal.rouge) }
        }
        v.bouton(c, getString(R.string.details), R.drawable.ic_info, v.pal.primaire, Vues.Style.CONTOUR) {
            fenetreDetails()
        }
    }

    private fun carteVerrouiller() {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_lock, getString(R.string.verrouiller_titre), v.pal.rouge)

        v.texte(c, getString(R.string.duree_verrou), 15f, v.pal.texte, gras = true)
        v.note(c, getString(R.string.duree_aide))
        choixDuree(c, DUREES, duree) { duree = it; dessiner() }

        v.espace(c, 10)
        v.texte(c, getString(R.string.delai_titre), 15f, v.pal.texte, gras = true)
        v.note(c, getString(R.string.delai_aide))
        choixDuree(c, DELAIS, delai) { delai = it; dessiner() }

        v.espace(c, 10)
        v.texte(c, getString(R.string.resume_verrou, Apparence.enClair(this, duree), Apparence.enClair(this, delai)),
            15f, v.pal.primaire, gras = true)

        v.bouton(c, getString(R.string.btn_verrouiller), R.drawable.ic_lock, v.pal.rouge) {
            val d = duree
            val r = delai
            confirmer(
                getString(R.string.confirmer_verrou_titre, Apparence.enClair(this, d)),
                getString(R.string.confirmer_verrou_texte, Apparence.enClair(this, r)),
                R.string.mot_verrouiller, "VERROUILLER", R.drawable.ic_lock,
            ) { action(R.string.ok_verrou) { Verrou.verrouiller(this, d, r) } }
        }
    }

    private fun carteVerrouActif(e: Etat) {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_hourglass_empty, getString(R.string.verrou_actif_titre))
        val delaiTexte = Apparence.enClair(this, e.delaiRondes * Drand.PERIODE_S)

        if (e.rondeLiberation == 0L) {
            v.note(c, getString(R.string.sortie_info, delaiTexte))
            v.bouton(c, getString(R.string.demander_sortie), R.drawable.ic_schedule, v.pal.primaire, Vues.Style.CONTOUR) {
                action(R.string.ok_demande) { Verrou.demanderSortie(this) }
            }
        } else {
            v.note(c, getString(R.string.sortie_demandee, dateLongue(Drand.dateRondeMs(e.rondeLiberation))))
            v.bouton(c, getString(R.string.annuler_demande), R.drawable.ic_verified_user, v.pal.vert) {
                action(R.string.ok_annule) { Verrou.annulerDemande(this) }
            }
            v.bouton(c, getString(R.string.liberer), R.drawable.ic_lock_open, v.pal.rouge, Vues.Style.CONTOUR) {
                confirmer(getString(R.string.liberer_titre), "", R.string.mot_liberer, "LIBERER", R.drawable.ic_lock_open) {
                    action(R.string.ok_libere) { Verrou.liberer(this) }
                }
            }
        }
    }

    private fun carteProlonger() {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_update, getString(R.string.prolonger_titre), v.pal.vert)
        v.note(c, getString(R.string.prolonger_aide))
        choixDuree(c, DUREES, prolongation) { prolongation = it; dessiner() }
        v.bouton(c, getString(R.string.prolonger) + " · " + Apparence.enClair(this, prolongation),
            R.drawable.ic_update, v.pal.vert) {
            val d = prolongation
            action(R.string.ok_prolonge) { Verrou.prolonger(this, d) }
        }
    }

    private fun carteRetirer() {
        val c = v.carte(racine)
        v.entete(c, R.drawable.ic_delete, getString(R.string.retirer_titre), v.pal.texteDoux)
        v.bouton(c, getString(R.string.desinstaller), R.drawable.ic_delete, v.pal.rouge, Vues.Style.CONTOUR) {
            confirmer(getString(R.string.desinstaller_titre), "", R.string.mot_desinstaller, "DESINSTALLER", R.drawable.ic_delete) {
                Protections.retirer(this)
                Protections.abandonnerRole(this)
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }
        }
    }

    /** Puces de durées toutes faites, plus « Autre… » qui ouvre une fenêtre de saisie. */
    private fun choixDuree(parent: LinearLayout, prereglages: List<Long>, valeur: Long, choix: (Long) -> Unit) {
        val libelles = prereglages.map { Apparence.court(this, it) } + getString(R.string.autre)
        val i = prereglages.indexOf(valeur).let { if (it < 0) prereglages.size else it }
        v.puces(parent, libelles, i, parLigne = 4) { k ->
            if (k < prereglages.size) choix(prereglages[k]) else fenetreDuree(valeur, choix)
        }
    }

    // ---------------------------------------------------------------- fenêtres

    private fun fenetre(titre: String, message: String, iconeId: Int? = null, teinter: Boolean = true) {
        val b = AlertDialog.Builder(this).setTitle(titre).setMessage(message)
            .setPositiveButton(R.string.fermer, null)
        if (iconeId != null) b.setIcon(if (teinter) iconeTeinte(iconeId, v.pal.primaire) else getDrawable(iconeId))
        b.show()
    }

    private fun iconeTeinte(id: Int, couleur: Int) = getDrawable(id)?.mutate()?.apply { setTint(couleur) }

    private fun fenetreCouche(couche: Protections.Couche, ok: Boolean) {
        val b = AlertDialog.Builder(this)
            .setTitle(getString(couche.nom))
            .setMessage(getString(couche.aide))
            .setIcon(iconeTeinte(if (ok) R.drawable.ic_check_circle else R.drawable.ic_cancel, if (ok) v.pal.vert else v.pal.rouge))
            .setNegativeButton(R.string.fermer, null)
        if (!ok && couche == Protections.Couche.GARDE) {
            if (Gardien.peutReactiver(this)) {
                b.setPositiveButton(R.string.activer_garde) { _, _ ->
                    Gardien.reactiverGarde(this)
                    racine.postDelayed({ dessiner() }, 800)
                }
            } else {
                b.setPositiveButton(R.string.ouvrir_accessibilite) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        b.show()
    }

    private fun texteAnalyse(): String = when (DiagDetection.dernier) {
        Analyse.AUCUNE -> getString(R.string.an_aucune)
        Analyse.REFUSEE -> getString(R.string.an_refusee, DiagDetection.dernierDetail)
        Analyse.NOIR -> getString(R.string.an_noir)
        Analyse.INCHANGE -> getString(R.string.an_inchange)
        Analyse.RIEN -> getString(R.string.an_rien)
        Analyse.MASQUE -> getString(R.string.an_masque, DiagDetection.dernierDetail)
    }

    private fun fenetreDetails() {
        val lignes = mutableListOf(getString(R.string.diag_derniere, texteAnalyse()))
        if (DiagDetection.dernierMs > 0) lignes += getString(R.string.diag_ms, DiagDetection.dernierMs.toInt())
        lignes += getString(R.string.diag_nb, DiagDetection.analyses)
        lignes += getString(R.string.couche_modele) + " : " + (if (DiagDetection.modeleCharge) "✔" else "✘")
        lignes += getString(R.string.diag_floutees, DiagDetection.floutees)
        DiagDetection.erreur?.let { lignes += getString(R.string.diag_erreur, it) }
        fenetre(getString(R.string.details_titre), lignes.joinToString("\n\n"), R.drawable.ic_info)
    }

    private fun fenetreDuree(valeur: Long, choix: (Long) -> Unit) {
        val unites = Apparence.unites(this)
        val u = unites.indexOfLast { valeur % it.second == 0L }.coerceAtLeast(0)
        val nombre = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((valeur / unites[u].second).toString())
            setSelectAllOnFocus(true)
        }
        val liste = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, unites.map { it.first })
            setSelection(u)
        }
        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(v.dp(20), v.dp(8), v.dp(20), 0)
            addView(nombre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(liste, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.choisir_duree)
            .setIcon(iconeTeinte(R.drawable.ic_schedule, v.pal.primaire))
            .setView(boite)
            .setNegativeButton(R.string.annuler, null)
            .setPositiveButton(R.string.confirmer) { _, _ ->
                val n = nombre.text.toString().trim().toLongOrNull()
                if (n == null || n <= 0 || n > 100_000) dire(getString(R.string.duree_invalide))
                else choix(n * unites[liste.selectedItemPosition].second)
            }
            .show()
    }

    private fun fenetreReglages() {
        val e = Etat(this)
        val elements = arrayOf(getString(R.string.langue), getString(R.string.theme), getString(R.string.a_propos))
        AlertDialog.Builder(this)
            .setTitle(R.string.reglages)
            .setIcon(iconeTeinte(R.drawable.ic_settings, v.pal.primaire))
            .setItems(elements) { _, i ->
                when (i) {
                    0 -> {
                        val codes = listOf("") + Apparence.LANGUES.map { it.first }
                        val noms = (listOf(getString(R.string.langue_systeme)) + Apparence.LANGUES.map { it.second }).toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(R.string.langue)
                            .setIcon(iconeTeinte(R.drawable.ic_language, v.pal.primaire))
                            .setSingleChoiceItems(noms, codes.indexOf(e.langue).coerceAtLeast(0)) { d, k ->
                                d.dismiss()
                                if (codes[k] != e.langue) { e.langue = codes[k]; recreate() }
                            }
                            .setNegativeButton(R.string.annuler, null)
                            .show()
                    }
                    1 -> {
                        val noms = arrayOf(getString(R.string.theme_systeme), getString(R.string.theme_clair), getString(R.string.theme_sombre))
                        AlertDialog.Builder(this)
                            .setTitle(R.string.theme)
                            .setIcon(iconeTeinte(R.drawable.ic_brightness_4, v.pal.primaire))
                            .setSingleChoiceItems(noms, e.theme) { d, k ->
                                d.dismiss()
                                if (k != e.theme) { e.theme = k; recreate() }
                            }
                            .setNegativeButton(R.string.annuler, null)
                            .show()
                    }
                    else -> fenetre(getString(R.string.a_propos),
                        getString(R.string.a_propos_texte, versionApp()), R.drawable.ic_prp, teinter = false)
                }
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun versionApp(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (ex: Exception) { "" }

    /** Fenêtre de confirmation où il faut taper un mot (dans la langue choisie, ou le mot français). */
    private fun confirmer(titre: String, message: String, motId: Int, motFrancais: String, iconeId: Int, suite: () -> Unit) {
        val mot = getString(motId)
        val saisie = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = mot
        }
        val boite = LinearLayout(this).apply {
            setPadding(v.dp(20), v.dp(8), v.dp(20), 0)
            addView(saisie, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val texte = (if (message.isEmpty()) "" else message + "\n\n") + getString(R.string.taper_mot, mot)
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setIcon(iconeTeinte(iconeId, v.pal.rouge))
            .setMessage(texte)
            .setView(boite)
            .setNegativeButton(R.string.annuler, null)
            .setPositiveButton(R.string.confirmer) { _, _ ->
                val t = saisie.text.toString().trim().uppercase(Apparence.locale(this))
                if (t == mot.uppercase(Apparence.locale(this)) || t == motFrancais) suite()
                else dire(getString(R.string.mot_incorrect))
            }
            .show()
    }

    // ---------------------------------------------------------------- actions

    /** Lance une opération réseau hors du fil principal, puis redessine. */
    private fun action(succes: Int, f: () -> String?) {
        if (occupe) return
        occupe = true
        dire(getString(R.string.un_instant))
        thread {
            val erreur = try { f() } catch (ex: Exception) { getString(R.string.diag_erreur, ex.message ?: "") }
            runOnUiThread {
                occupe = false
                dire(erreur ?: getString(succes))
                dessiner()
            }
        }
    }

    private fun dire(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dateLongue(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Apparence.locale(this)).format(Date(ms))

    companion object {
        val DUREES = listOf(5 * MINUTE, 1 * HEURE, 1 * JOUR, 7 * JOUR, 30 * JOUR, 3 * MOIS, 12 * MOIS)
        val DELAIS = listOf(2 * MINUTE, 1 * HEURE, 24 * HEURE, 48 * HEURE, 7 * JOUR)
    }
}
