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
     * Ce qui fait flouter une image, par défaut : nudité réelle seulement — sexe, anus, fesses
     * nues, seins nus. Maillots de bain, sous-vêtements, torses d'homme, ventres, pieds et
     * visages ne floutent jamais rien.
     */
    private val SEUILS = mapOf(
        4 to 0.45f,   // FEMALE_GENITALIA_EXPOSED
        14 to 0.45f,  // MALE_GENITALIA_EXPOSED
        6 to 0.45f,   // ANUS_EXPOSED
        3 to 0.55f,   // FEMALE_BREAST_EXPOSED (un torse d'homme ne doit pas suffire)
        2 to 0.60f,   // BUTTOCKS_EXPOSED (un short de sport ne doit pas suffire)
    )

    /** Mode enfant : floute en plus les torses nus et les maillots ou sous-vêtements. */
    private val SEUILS_ENFANT = mapOf(
        5 to 0.50f,   // MALE_BREAST_EXPOSED (torse nu)
        16 to 0.55f,  // FEMALE_BREAST_COVERED (soutien-gorge, haut de maillot)
        0 to 0.55f,   // FEMALE_GENITALIA_COVERED (culotte, bas de maillot)
        17 to 0.60f,  // BUTTOCKS_COVERED
    )

    fun visee(classe: Int, enfant: Boolean = false): Boolean =
        classe in SEUILS || (enfant && classe in SEUILS_ENFANT)

    const val POITRINE_F = 3
    const val POITRINE_H = 5

    /** Seuil de premier repérage sur tout l'écran ; chaque suspect est ensuite revérifié de près. */
    fun seuilSuspect(classe: Int, sensibilite: Int, enfant: Boolean = false): Float =
        (seuil(classe, sensibilite, enfant) - 0.15f).coerceAtLeast(0.25f)

    /** Sensibilité : 0 = stricte (masque plus facilement), 1 = normale, 2 = souple. */
    fun seuil(classe: Int, sensibilite: Int, enfant: Boolean = false): Float {
        val base = SEUILS[classe] ?: (if (enfant) SEUILS_ENFANT[classe] else null) ?: return 2f
        val decalage = when (sensibilite) { 0 -> -0.10f; 2 -> 0.10f; else -> 0f }
        return (base + decalage).coerceIn(0.15f, 0.90f)
    }
}
