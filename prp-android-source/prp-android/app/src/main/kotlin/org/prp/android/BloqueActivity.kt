package org.prp.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Écran affiché à la place d'un site bloqué. */
class BloqueActivity : Activity() {
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = (24 * resources.displayMetrics.density).toInt()
        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(m, m, m, m)
            setBackgroundColor(Color.rgb(31, 58, 95))
        }
        boite.addView(TextView(this).apply {
            text = "Site bloqué par PrP"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        detail = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(210, 222, 238))
            gravity = Gravity.CENTER
            setPadding(0, m, 0, m)
        }
        boite.addView(detail)
        boite.addView(Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(boite)
        afficher(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        afficher(intent)
    }

    private fun afficher(i: Intent?) {
        val hote = i?.getStringExtra(HOTE).orEmpty()
        val raison = i?.getStringExtra(RAISON).orEmpty()
        detail.text = buildString {
            if (hote.isNotEmpty()) append(hote).append('\n')
            if (raison.isNotEmpty()) append("Motif : ").append(raison).append('\n')
            append("\nTu as choisi ce blocage toi-même. Tiens bon.")
        }
    }

    companion object {
        const val HOTE = "hote"
        const val RAISON = "raison"
    }
}
