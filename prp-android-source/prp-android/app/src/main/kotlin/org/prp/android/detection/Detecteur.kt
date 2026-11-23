package org.prp.android.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Enveloppe du modèle NudeNet 320n exécuté sur le téléphone avec ONNX Runtime. */
class Detecteur(modele: ByteArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val nomEntree: String

    init {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        session = env.createSession(modele, options)
        nomEntree = session.inputNames.iterator().next()
    }

    class Resultat(val boites: List<Boite>, val msInference: Long)

    /**
     * [pixels] : image déjà redimensionnée à [l] x [h] (multiples de 32).
     * [largeurImage] et [hauteurImage] : taille de l'écran réel, pour replacer les boîtes.
     */
    fun detecter(pixels: IntArray, l: Int, h: Int, largeurImage: Float, hauteurImage: Float): Resultat {
        val debut = System.nanoTime()
        val donnees = Pretraitement.versCHW(pixels, l, h)
        val forme = longArrayOf(1, 3, h.toLong(), l.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(donnees), forme).use { entree ->
            session.run(mapOf(nomEntree to entree)).use { res ->
                val sortie = res[0] as OnnxTensor
                val ancres = sortie.info.shape[2].toInt()
                val tampon = sortie.floatBuffer
                val tab = FloatArray(tampon.remaining())
                tampon.get(tab)
                val boites = Yolo.decoder(tab, ancres, l, h, largeurImage, hauteurImage)
                return Resultat(boites, (System.nanoTime() - debut) / 1_000_000L)
            }
        }
    }

    override fun close() {
        try { session.close() } catch (e: Exception) { }
    }
}
