package org.prp.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.prp.android.filtre.Raison

/** Écran affiché à la place d'un site bloqué. */
class BloqueActivity : Activity() {
    private lateinit var hoteVue: TextView
    private lateinit var motifVue: TextView

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Apparence.envelopper(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Apparence.palette(this)
        val v = Vues(this, pal)
        window.statusBarColor = pal.fond
        window.navigationBarColor = pal.fond

        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(v.dp(24), v.dp(24), v.dp(24), v.dp(24))
            setBackgroundColor(pal.fond)
        }
        val carte = v.carte(boite)
        carte.gravity = Gravity.CENTER_HORIZONTAL
        carte.addView(v.icone(R.drawable.ic_lock, pal.rouge, 56).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        v.espace(carte, 10)
        v.texte(carte, getString(R.string.bloque_titre), 22f, pal.texte, gras = true).gravity = Gravity.CENTER
        hoteVue = v.texte(carte, "", 16f, pal.primaire, gras = true).apply { gravity = Gravity.CENTER }
        motifVue = v.note(carte, "").apply { gravity = Gravity.CENTER }
        v.espace(carte, 8)
        v.texte(carte, getString(R.string.tiens_bon), 15f, pal.vert).gravity = Gravity.CENTER
        v.bouton(carte, getString(R.string.fermer), R.drawable.ic_cancel) { finish() }

        setContentView(boite)
        afficher(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        afficher(intent)
    }

    private fun afficher(i: Intent?) {
        hoteVue.text = i?.getStringExtra(HOTE).orEmpty()
        val raison = try { Raison.valueOf(i?.getStringExtra(RAISON).orEmpty()) } catch (e: Exception) { null }
        val libelle = when (raison) {
            Raison.MOT_CLE -> getString(R.string.raison_mot_cle)
            Raison.LISTE -> getString(R.string.raison_liste)
            Raison.MOTEUR -> getString(R.string.raison_moteur)
            Raison.APPRIS -> getString(R.string.raison_appris)
            null -> ""
        }
        motifVue.text = if (libelle.isEmpty()) "" else getString(R.string.motif, libelle)
    }

    companion object {
        const val HOTE = "hote"
        const val RAISON = "raison"
    }
}
