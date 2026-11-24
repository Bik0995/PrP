package org.prp.android.detection

/** Les 18 classes du modèle NudeNet 320n, dans l'ordre de sortie du modèle. */
object Classes {
    val NOMS = arrayOf(
        "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
        "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
        "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED",
    )
    const val NB = 18

    /**
     * Seules ces cinq classes déclenchent un masque (« ne rien bloquer d'autre ») :
     * parties génitales, poitrine féminine, fesses et anus, quand elles sont EXPOSÉES.
     * Visages, ventre, pieds, aisselles et parties couvertes ne masquent jamais rien.
     */
    private val SEUILS = mapOf(
        4 to 0.45f,   // FEMALE_GENITALIA_EXPOSED
        14 to 0.45f,  // MALE_GENITALIA_EXPOSED
        6 to 0.45f,   // ANUS_EXPOSED
        3 to 0.55f,   // FEMALE_BREAST_EXPOSED (un torse d'homme ne doit pas suffire)
        2 to 0.60f,   // BUTTOCKS_EXPOSED (un short de sport ne doit pas suffire)
    )

    fun visee(classe: Int): Boolean = classe in SEUILS

    const val POITRINE_F = 3
    const val POITRINE_H = 5

    /** Seuil de premier repérage sur tout l'écran ; chaque suspect est ensuite revérifié de près. */
    fun seuilSuspect(classe: Int, sensibilite: Int): Float = (seuil(classe, sensibilite) - 0.15f).coerceAtLeast(0.25f)

    /** Sensibilité : 0 = stricte (masque plus facilement), 1 = normale, 2 = souple. */
    fun seuil(classe: Int, sensibilite: Int): Float {
        val base = SEUILS[classe] ?: return 2f
        val decalage = when (sensibilite) { 0 -> -0.10f; 2 -> 0.10f; else -> 0f }
        return (base + decalage).coerceIn(0.15f, 0.90f)
    }
}
