package org.prp.android.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Le classificateur NSFWJS MobileNetV2 (MIT), converti en ONNX, exécuté sur le téléphone. */
class Classifieur(modele: ByteArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val nomEntree: String

    init {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        session = env.createSession(modele, options)
        nomEntree = session.inputNames.iterator().next()
    }

    /** [pixels] : image ARGB de 224 x 224. Renvoie les 5 probabilités (voir [Classement]). */
    fun classer(pixels: IntArray): FloatArray {
        val c = Classement.COTE
        val donnees = Pretraitement.versCHW(pixels, c, c)
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
