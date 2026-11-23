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
import app.meineschule.expressive.core.Dates
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Lokale Erinnerungen an anstehende Tests – mit Hinweis, was man wegen Fehlzeiten in dem Fach noch nachholen muss. */
object Reminders {
    const val CHANNEL = "tests"
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Tests & Nachholen", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork("tests"); return }
        wm.enqueueUniquePeriodicWork("tests", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TestReminderWorker>(4, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class TestReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MsdApp).container
        if (!c.prefs.testReminders.value) return Result.success()
        val now = LocalTime.now(); if (now.hour < 7 || now.hour >= 21) return Result.success()      // nachts keine Benachrichtigungen
        if (!c.auth.isSignedIn && !c.auth.restore()) return Result.success()
        return try {
            if (c.api.school == null) c.api.school = c.prefs.school.value ?: c.auth.savedSchool
            if (c.api.school == null) return Result.success()
            val today = LocalDate.now(); val lead = c.prefs.testLead.value
            val events = c.repos.calendar.index().filter { c.subjects.isTest(it.title) }.mapNotNull { e -> Dates.date(e.start)?.let { d -> if (!d.isBefore(today) && !d.isAfter(today.plusDays(lead.toLong()))) e to d else null } }
            val students = c.repos.school.index().students
            for ((e, d) in events) {
                val key = "${e.id}:$today"; if (c.prefs.wasReminded(key)) continue
                val hit = students.firstNotNullOfOrNull { s -> c.subjects.forEvent(e.title, s.id)?.let { s to it } } ?: continue
                val (st, es) = hit; val miss = es.missed.rows; val hours = miss.sumOf { it.hours }
                val whenText = when (java.time.temporal.ChronoUnit.DAYS.between(today, d)) { 0L -> "Heute"; 1L -> "Morgen"; else -> Dates.dayShort(d) }
                val body = buildString {
                    append("${e.title} – $whenText.")
                    if (hours > 0) append(" Du hast ${es.subject.name} $hours ${if (hours == 1) "Stunde" else "Stunden"} verpasst (${miss.take(3).joinToString(", ") { Dates.dayShort(it.date) }}) – bitte Stoff nachholen.") else append(" In ${es.subject.name} gibt es nichts nachzuholen.")
                }
                Reminders.notify(applicationContext, (e.id.hashCode() and 0x7fffffff) % 100000 + 100, "Test in ${es.subject.name}" + if (students.size > 1) " · ${st.name}" else "", body)
                c.prefs.markReminded(key)
            }
            Result.success()
        } catch (ex: Exception) { Result.retry() }
    }
}
