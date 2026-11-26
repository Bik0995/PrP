package org.prp.android.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Un classificateur d'images ONNX exécuté sur le téléphone : NSFWJS MobileNetV2 (MIT, 224 px,
 * sortie : 5 probabilités) ou Marqo nsfw-image-detection-384 (Apache 2.0, 384 px, sortie :
 * 2 logits NSFW / SFW).
 */
class Classifieur(
    modele: ByteArray,
    val cote: Int = Classement.COTE,
    private val moyenne: Float = 0f,
    private val ecartType: Float = 1f,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val nomEntree: String

    init {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        session = env.createSession(modele, options)
        nomEntree = session.inputNames.iterator().next()
    }

    /** [pixels] : image ARGB de [cote] x [cote]. Renvoie la sortie brute du modèle. */
    fun classer(pixels: IntArray): FloatArray {
        val c = cote
        val donnees = Pretraitement.versCHW(pixels, c, c, moyenne, ecartType)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(donnees), longArrayOf(1, 3, c.toLong(), c.toLong())).use { entree ->
            session.run(mapOf(nomEntree to entree)).use { res ->
                val t = res[0] as OnnxTensor
                val b = t.floatBuffer
                val p = FloatArray(b.remaining())
                b.get(p)
                return p
            }
        }
    }

    override fun close() {
        try { session.close() } catch (e: Exception) { }
    }
}

/** Probabilité de la première classe (« NSFW » pour Marqo) à partir de logits. */
fun probabilitePremiere(logits: FloatArray): Float {
    val m = logits.max()
    var somme = 0.0
    for (v in logits) somme += Math.exp((v - m).toDouble())
    return (Math.exp((logits[0] - m).toDouble()) / somme).toFloat()
}
