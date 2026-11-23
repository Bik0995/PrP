package org.prp.android

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Petites briques d'interface dessinées en code (cartes, boutons, puces, lignes d'état),
 * aux couleurs de la palette claire ou sombre.
 */
class Vues(private val a: Activity, val pal: Apparence.Palette) {

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * a.resources.displayMetrics.density

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    fun fond(couleur: Int, rayon: Float = 16f, contour: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(couleur)
            cornerRadius = dpf(rayon)
            if (contour != null) setStroke(dp(1), contour)
        }

    /** Fond avec effet d'appui. */
    private fun cliquable(contenu: Drawable, rayon: Float): Drawable {
        val masque = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dpf(rayon) }
        val onde = (pal.texte and 0x00FFFFFF) or 0x33000000
        return RippleDrawable(ColorStateList.valueOf(onde), contenu, masque)
    }

    /** Icône teintée (couleur null : couleurs d'origine, pour le logo). */
    fun icone(id: Int, couleur: Int?, taille: Int = 24): ImageView = ImageView(a).apply {
        setImageResource(id)
        if (couleur != null) imageTintList = ColorStateList.valueOf(couleur)
        layoutParams = LinearLayout.LayoutParams(dp(taille), dp(taille))
    }

    fun texte(parent: ViewGroup, t: CharSequence, taille: Float = 15f, couleur: Int = pal.texte, gras: Boolean = false): TextView {
        val v = TextView(a).apply {
            text = t
            textSize = taille
            setTextColor(couleur)
            if (gras) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(2), 0, dp(2))
        }
        parent.addView(v, LinearLayout.LayoutParams(mp, wc))
        return v
    }

    fun note(parent: ViewGroup, t: CharSequence) = texte(parent, t, 14f, pal.texteDoux)

    fun espace(parent: ViewGroup, hauteur: Int) =
        parent.addView(View(a), LinearLayout.LayoutParams(1, dp(hauteur)))

    /** Carte arrondie ; renvoie son contenu vertical. */
    fun carte(parent: ViewGroup, couleur: Int = pal.carte, contour: Int? = pal.bordure): LinearLayout {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = fond(couleur, 18f, contour)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            elevation = dpf(1f)
        }
        parent.addView(c, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(12) })
        return c
    }

    /** En-tête de carte : icône, titre, et bouton d'aide facultatif. */
    fun entete(carte: ViewGroup, iconeId: Int, titre: String, couleur: Int = pal.primaire, aide: (() -> Unit)? = null) {
        val ligne = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        ligne.addView(icone(iconeId, couleur, 24))
        ligne.addView(TextView(a).apply {
            text = titre
            textSize = 18f
            setTextColor(pal.texte)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, wc, 1f))
        if (aide != null) ligne.addView(boutonIcone(R.drawable.ic_help_outline, pal.texteDoux, null, aide))
        carte.addView(ligne, LinearLayout.LayoutParams(mp, wc))
    }

    fun boutonIcone(iconeId: Int, couleur: Int, description: String? = null, clic: () -> Unit): ImageButton =
        ImageButton(a).apply {
            setImageResource(iconeId)
            imageTintList = ColorStateList.valueOf(couleur)
            val tv = TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = description
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { clic() }
        }

    enum class Style { PLEIN, CONTOUR }

    fun bouton(parent: ViewGroup, t: String, iconeId: Int? = null, couleur: Int = pal.primaire,
               style: Style = Style.PLEIN, clic: () -> Unit): Button {
        val surCouleur = if (style == Style.PLEIN) pal.surPrimaire else couleur
        val b = Button(a).apply {
            text = t
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(surCouleur)
            stateListAnimator = null
            minHeight = dp(52)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = cliquable(
                if (style == Style.PLEIN) fond(couleur, 14f) else fond(0, 14f, couleur), 14f,
            )
            if (iconeId != null) {
                val ic = a.getDrawable(iconeId)?.mutate()
                ic?.setTint(surCouleur)
                setCompoundDrawablesRelativeWithIntrinsicBounds(ic, null, null, null)
                compoundDrawablePadding = dp(8)
            }
            setOnClickListener { clic() }
        }
        parent.addView(b, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) })
        return b
    }

    /** Rangée de puces à choix unique, réparties sur plusieurs lignes. */
    fun puces(parent: ViewGroup, options: List<String>, choisi: Int, parLigne: Int = 4,
              actif: (Int) -> Boolean = { true }, clic: (Int) -> Unit) {
        var ligne: LinearLayout? = null
        options.forEachIndexed { i, libelle ->
            if (i % parLigne == 0) {
                ligne = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(ligne, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })
            }
            val sel = i == choisi
            val ok = actif(i)
            val p = TextView(a).apply {
                text = libelle
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setTypeface(typeface, if (sel) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (sel) pal.surPrimaire else if (ok) pal.texte else pal.bordure)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = cliquable(
                    if (sel) fond(pal.primaire, 20f) else fond(pal.carte, 20f, pal.bordure), 20f,
                )
                isEnabled = ok
                setOnClickListener { clic(i) }
            }
            ligne!!.addView(p, LinearLayout.LayoutParams(0, wc, 1f).apply {
                marginEnd = dp(6)
            })
        }
        // complète la dernière ligne pour garder des puces de même largeur
        val reste = (parLigne - options.size % parLigne) % parLigne
        repeat(reste) {
            ligne?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(6) })
        }
    }

    /** Ligne d'état : coche verte ou croix rouge, libellé, touchable pour l'explication. */
    fun ligneEtat(parent: ViewGroup, ok: Boolean, libelle: String, clic: () -> Unit) {
        val couleur = if (ok) pal.vert else pal.rouge
        val ligne = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = cliquable(fond(if (ok) 0 else pal.fondRouge, 12f), 12f)
            setOnClickListener { clic() }
        }
        ligne.addView(icone(if (ok) R.drawable.ic_check_circle else R.drawable.ic_cancel, couleur, 22))
        ligne.addView(TextView(a).apply {
            text = libelle
            textSize = 15f
            setTextColor(if (ok) pal.texte else couleur)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, wc, 1f))
        if (!ok) ligne.addView(icone(R.drawable.ic_help_outline, couleur, 18))
        parent.addView(ligne, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(2) })
    }
}
