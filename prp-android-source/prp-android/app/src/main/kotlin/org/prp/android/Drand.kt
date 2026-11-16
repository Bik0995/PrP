package org.prp.android

import java.net.HttpURLConnection
import java.net.URL

/**
 * Horloge infalsifiable de PrP : le réseau public drand « quicknet » publie une nouvelle
 * « ronde » numérotée toutes les 3 secondes. PrP ne mesure jamais le temps avec l'horloge
 * du téléphone (qu'on peut changer) : une échéance est un NUMÉRO DE RONDE, et elle n'est
 * atteinte que lorsque les relais drand annoncent cette ronde, en HTTPS.
 */
object Drand {
    const val CHAINE = "52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971"
    const val GENESE_S = 1692803367L
    const val PERIODE_S = 3L

    val RELAIS = listOf(
        "https://api.drand.sh",
        "https://api2.drand.sh",
        "https://api3.drand.sh",
        "https://drand.cloudflare.com",
    )

    private val CHAMP_RONDE = Regex("\"round\"\\s*:\\s*(\\d{1,18})")

    fun extraireRonde(json: String): Long? = CHAMP_RONDE.find(json)?.groupValues?.get(1)?.toLongOrNull()

    /** Nombre de rondes couvrant une durée (arrondi vers le haut). */
    fun rondes(dureeS: Long): Long = (dureeS + PERIODE_S - 1) / PERIODE_S

    /** Date (ms) à laquelle une ronde est publiée : pour l'AFFICHAGE seulement. */
    fun dateRondeMs(ronde: Long): Long = (GENESE_S + (ronde - 1) * PERIODE_S) * 1000

    /** Ronde en cours selon les relais, ou null si aucun ne répond. À appeler hors du fil principal. */
    fun rondeActuelle(): Long? {
        for (relais in RELAIS) {
            try {
                val c = URL("$relais/$CHAINE/public/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.useCaches = false
                try {
                    if (c.responseCode != 200) continue
                    val corps = c.inputStream.bufferedReader().use { it.readText() }
                    val r = extraireRonde(corps)
                    if (r != null && r > 0) return r
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                // relais suivant
            }
        }
        return null
    }
}
