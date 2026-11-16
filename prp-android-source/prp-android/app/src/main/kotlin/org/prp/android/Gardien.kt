package org.prp.android

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import kotlin.concurrent.thread

/** Le gardien : remet régulièrement toutes les protections en place tant que le verrou est actif. */
object Gardien {
    private const val PERIODE_MS = 5 * 60_000L

    @Synchronized
    fun cycle(ctx: Context, avecDns: Boolean) {
        val app = ctx.applicationContext
        if (!Etat(app).verrouActif) return
        Protections.appliquer(app, avecDns)
        reactiverGarde(app)
    }

    /** Réveil périodique, indépendant de la garde d'accessibilité. */
    fun programmer(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ReveilReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000L, PERIODE_MS, pi,
        )
    }

    private fun composantGarde(ctx: Context) = ComponentName(ctx, GardeService::class.java)

    fun gardeActive(ctx: Context): Boolean {
        val liste = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val moi = composantGarde(ctx)
        return liste.split(':').any { ComponentName.unflattenFromString(it) == moi }
    }

    fun peutReactiver(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Active la garde sans passer par les réglages (permission donnée par adb). */
    fun reactiverGarde(ctx: Context): Boolean {
        if (gardeActive(ctx)) return true
        if (!peutReactiver(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val actuel = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val moi = composantGarde(ctx).flattenToString()
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (actuel.isEmpty()) moi else "$actuel:$moi",
            )
            Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        } catch (e: Exception) { false }
    }
}

class ReveilReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val attente = goAsync()
        thread {
            try { Gardien.cycle(ctx, avecDns = false) } finally { attente.finish() }
        }
    }
}

class DemarrageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Gardien.programmer(ctx)
        val attente = goAsync()
        thread {
            try { Gardien.cycle(ctx, avecDns = false) } finally { attente.finish() }
        }
    }
}
