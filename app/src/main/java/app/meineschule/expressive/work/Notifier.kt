package app.meineschule.expressive.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.meineschule.expressive.MainActivity
import app.meineschule.expressive.MsdApp
import java.util.concurrent.TimeUnit

/** Lokale Benachrichtigungen. Inhalte von Nachrichten werden bewusst NICHT angezeigt (nur Anzahl). */
object Notifier {
    const val CHANNEL = "messages"
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Nachrichten", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun notifyUnread(ctx: Context, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("MeineSchule")
            .setContentText(if (count == 1) "1 ungelesene Nachricht" else "$count ungelesene Nachrichten").setContentIntent(pi).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }
    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork("poll"); return }
        wm.enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

/**
 * Hintergrund-Abfrage von School/State (alle ≥15 Min). Funktioniert nur mit „Angemeldet bleiben“ (Sitzung aus dem Keystore).
 * Echtes Push (FCM) ist nicht umgesetzt: Die Registrierung nutzt das native DeviceInstallation-Modell der Original-App
 * (nicht belegt für Drittclients) und erfordert eine eigene Push-Infrastruktur.
 */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MsdApp).container
        if (c.inForeground) return Result.success()
        if (!c.auth.isSignedIn && !c.auth.restore()) return Result.success()
        return try {
            if (c.api.school == null) c.api.school = c.prefs.school.value ?: c.auth.savedSchool
            if (c.api.school == null) return Result.success()
            val unread = c.repos.school.state().unreadChatMessageCount
            if (unread > c.prefs.lastNotifiedUnread) Notifier.notifyUnread(applicationContext, unread)
            c.prefs.lastNotifiedUnread = unread
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}
