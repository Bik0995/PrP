package org.prp.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** Écran unique de PrP : état des protections, verrouillage, prolongation, sortie. */
class MainActivity : Activity() {

    private lateinit var racine: LinearLayout
    private var occupe = false

    private val bleu = Color.rgb(31, 58, 95)
    private val vert = Color.rgb(27, 122, 61)
    private val rouge = Color.rgb(176, 42, 42)
    private val gris = Color.rgb(90, 96, 105)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(racine) })
    }

    override fun onResume() {
        super.onResume()
        Gardien.programmer(this)
        dessiner()
    }

    // ---------------------------------------------------------------- écran

    private fun dessiner() {
        racine.removeAllViews()
        val e = Etat(this)
        texte("PrP", 30f, bleu, gras = true)
        texte("Blocage du contenu adulte, verrouillé dans le temps", 15f, gris)
        if (Protections.estEssai(this))
            note("Version d'essai : le débogage USB reste ouvert et un PC peut retirer PrP. À ne pas utiliser pour le vrai verrou.")

        section("Protections")
        for ((nom, ok) in Protections.diagnostic(this))
            texte((if (ok) "✔  " else "✘  ") + nom, 16f, if (ok) vert else rouge)
        if (e.dnsDegrade)
            note("Les résolveurs DNS familiaux sont injoignables sur ce réseau : PrP a laissé le DNS en mode automatique pour ne pas couper Internet, et réessaie toutes les minutes. La garde des navigateurs reste active.")

        if (e.verrouActif) ecranVerrou(e) else ecranLibre()
        bouton("Actualiser", gris) { dessiner() }
    }

    private fun ecranLibre() {
        if (!Gardien.gardeActive(this)) {
            section("Étape 1 — la garde")
            if (Gardien.peutReactiver(this)) {
                bouton("Activer la garde des navigateurs", bleu) {
                    Gardien.reactiverGarde(this)
                    racine.postDelayed({ dessiner() }, 800)
                }
            } else {
                note("Active « PrP – Garde » dans les réglages d'accessibilité (ou lance le script d'installation depuis le PC, qui le fera tout seul).")
                bouton("Ouvrir les réglages d'accessibilité", bleu) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        if (!Protections.estProprietaire(this)) {
            section("Étape 2 — rendre PrP indélogeable")
            note("Pour que le verrou tienne, PrP doit devenir « propriétaire de l'appareil ». Cela se fait une seule fois, depuis un PC, avec le script outils/installer-android.bat (téléphone branché en USB, débogage USB activé, comptes retirés le temps de l'opération).\n\nEn attendant, le filtrage des navigateurs fonctionne déjà, mais il peut être désactivé.")
            return
        }

        section("Verrouiller")
        note("Durée du verrou : avant cette échéance, rien ni personne ne peut retirer PrP.")
        val duree = champDuree(30, 2)
        note("Délai de réflexion : attente entre ta demande de sortie et la sortie réelle.")
        val delai = champDuree(48, 1)
        bouton("VERROUILLER", rouge) {
            val d = duree()
            val r = delai()
            if (d == null || r == null) { dire("Indique deux durées valides."); return@bouton }
            confirmer(
                "Verrouiller pour ${enClair(d)} ?",
                "Aucun retour en arrière avant l'échéance, puis ${enClair(r)} de réflexion. " +
                    "L'heure du téléphone ne compte pas : seul le temps réel écoulé compte.\n\nTape VERROUILLER pour confirmer.",
                "VERROUILLER",
            ) { action("Verrou posé.") { Verrou.verrouiller(this, d, r) } }
        }

        section("Retirer PrP")
        bouton("Désinstaller PrP", gris) {
            confirmer("Désinstaller PrP ?", "Tape DESINSTALLER pour confirmer.", "DESINSTALLER") {
                Protections.retirer(this)
                Protections.abandonnerRole(this)
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun ecranVerrou(e: Etat) {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        section("Verrou actif")
        texte("Jusqu'au ${df.format(Date(Drand.dateRondeMs(e.rondeCible)))}", 20f, bleu, gras = true)
        val delai = enClair(e.delaiRondes * Drand.PERIODE_S)

        if (e.rondeLiberation == 0L) {
            note("Après cette date, tu pourras demander la sortie. Elle deviendra possible $delai plus tard.")
            bouton("Demander la sortie", bleu) { action("Demande enregistrée.") { Verrou.demanderSortie(this) } }
        } else {
            note("Sortie demandée. Elle sera possible à partir du ${df.format(Date(Drand.dateRondeMs(e.rondeLiberation)))}.")
            bouton("Libérer l'appareil", rouge) {
                confirmer("Retirer toutes les protections ?", "Tape LIBERER pour confirmer.", "LIBERER") {
                    action("Protections retirées.") { Verrou.liberer(this) }
                }
            }
            bouton("Annuler la demande (rester protégé)", vert) {
                action("Demande annulée.") { Verrou.annulerDemande(this) }
            }
        }

        section("Prolonger")
        note("La nouvelle durée compte à partir de maintenant. Le verrou n'est jamais raccourci, et une demande de sortie en cours est annulée.")
        val duree = champDuree(30, 2)
        bouton("Prolonger le verrou", vert) {
            val d = duree()
            if (d == null) { dire("Indique une durée valide."); return@bouton }
            action("Verrou prolongé.") { Verrou.prolonger(this, d) }
        }
    }

    // ---------------------------------------------------------------- actions

    /** Lance une opération réseau hors du fil principal, puis redessine. */
    private fun action(succes: String, f: () -> String?) {
        if (occupe) return
        occupe = true
        dire("Un instant…")
        thread {
            val erreur = try { f() } catch (ex: Exception) { "Erreur : ${ex.message}" }
            runOnUiThread {
                occupe = false
                dire(erreur ?: succes)
                dessiner()
            }
        }
    }

    private fun confirmer(titre: String, message: String, mot: String, suite: () -> Unit) {
        val saisie = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = mot
        }
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(message)
            .setView(saisie)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Confirmer") { _, _ ->
                if (saisie.text.toString().trim().uppercase() == mot) suite()
                else dire("Mot de confirmation incorrect : rien n'a été fait.")
            }
            .show()
    }

    private fun dire(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- petites briques

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texte(t: String, taille: Float, couleur: Int, gras: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = taille
            setTextColor(couleur)
            if (gras) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        racine.addView(v)
        return v
    }

    private fun section(t: String) {
        racine.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))
        texte(t, 19f, bleu, gras = true)
    }

    private fun note(t: String) = texte(t, 14f, gris)

    private fun bouton(t: String, couleur: Int, clic: () -> Unit) {
        val b = Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(Color.WHITE)
            background.setTint(couleur)
            setOnClickListener { if (!occupe) clic() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        racine.addView(b, lp)
    }

    /** Champ « nombre + unité ». Renvoie une fonction qui lit la durée en secondes (null si invalide). */
    private fun champDuree(valeur: Int, unite: Int): () -> Long? {
        val nombre = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(valeur.toString())
        }
        val choix = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, UNITES.map { it.first },
            )
            setSelection(unite)
        }
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ligne.addView(nombre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ligne.addView(choix, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        racine.addView(ligne)
        return {
            val n = nombre.text.toString().trim().toLongOrNull()
            if (n == null || n <= 0 || n > 100_000) null
            else n * UNITES[choix.selectedItemPosition].second
        }
    }

    companion object {
        val UNITES = listOf("minutes" to 60L, "heures" to 3600L, "jours" to 86_400L, "mois" to 30 * 86_400L)

        fun enClair(secondes: Long): String = when {
            secondes % (30 * 86_400L) == 0L -> "${secondes / (30 * 86_400L)} mois"
            secondes % 86_400L == 0L -> "${secondes / 86_400L} jour(s)"
            secondes % 3600L == 0L -> "${secondes / 3600L} heure(s)"
            else -> "${(secondes + 59) / 60} minute(s)"
        }
    }
}
