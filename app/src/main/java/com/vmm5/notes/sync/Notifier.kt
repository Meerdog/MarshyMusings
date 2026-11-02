package com.vmm5.notes.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vmm5.notes.R
import com.vmm5.notes.ui.MainActivity

/**
 * The notifications the user sees, kept few (0.14):
 *  - "Synced N notes", one notification for a run of syncs: notes synced
 *    within half an hour of the last ones add to the same notification.
 *    It may sound at most [Prefs.notifyMaxPerHour] times an hour; past that
 *    it updates without a sound.
 *  - The stick's battery being low, only if switched on.
 * The status Android requires while the app syncs or transcribes goes to
 * a minimised channel (no status bar icon, no sound) unless the user
 * asks to see it.
 */
object Notifier {
    const val CHANNEL_NOTES = "notes"
    const val CHANNEL_STATUS = "sync"            // the old, visible status channel
    const val CHANNEL_STATUS_QUIET = "status_quiet"
    const val CHANNEL_TRANSCRIBE = "transcribe"
    private const val ID_SYNCED = 2
    private const val ID_BATTERY = 3
    private const val BURST_MS = 30 * 60_000L
    private const val HOUR_MS = 60 * 60_000L

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Sync status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while the app looks for or pulls from the stick"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS_QUIET, "Sync status (minimised)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "The same, tucked away: no status bar icon"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TRANSCRIBE, "Transcribing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while a note is being transcribed"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NOTES, "Synced notes", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When notes have come off the stick"
        })
    }

    /** The channel for the working status: minimised unless the user wants it visible. */
    suspend fun statusChannel(c: Context, transcribing: Boolean = false): String =
        if (Prefs(c).quietStatus()) CHANNEL_STATUS_QUIET else if (transcribing) CHANNEL_TRANSCRIBE else CHANNEL_STATUS

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [n] notes came off the stick. */
    suspend fun synced(c: Context, n: Int) {
        val prefs = Prefs(c)
        if (n <= 0 || !prefs.notifySynced() || !allowed(c)) return
        channels(c)
        val sp = c.getSharedPreferences("notifier", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var count = if (now - sp.getLong("burst_at", 0L) > BURST_MS) 0 else sp.getInt("burst_count", 0)
        count += n
        val alerts = (sp.getString("alerts", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < HOUR_MS }
        val max = prefs.notifyMaxPerHour()
        val sound = alerts.size < max
        val nf = NotificationCompat.Builder(c, CHANNEL_NOTES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Synced $count note${if (count == 1) "" else "s"} from the stick")
            .setContentText("Transcribing in the background")
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.tango_yawn_large))
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .setSilent(!sound)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(ID_SYNCED, nf)
        sp.edit()
            .putLong("burst_at", now)
            .putInt("burst_count", count)
            .putString("alerts", (if (sound) alerts + now else alerts).joinToString(","))
            .apply()
    }

    suspend fun lowBattery(c: Context) {
        if (!Prefs(c).notifyBattery() || !allowed(c)) return
        channels(c)
        val nf = NotificationCompat.Builder(c, CHANNEL_NOTES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("The stick's battery is low")
            .setContentText("Plug it in when you can.")
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(ID_BATTERY, nf)
    }

    /** Android would not let the app start in the background, and the stick has new notes. */
    suspend fun cannotStart(c: Context) {
        if (!Prefs(c).notifySynced() || !allowed(c)) return
        channels(c)
        val nf = NotificationCompat.Builder(c, CHANNEL_NOTES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("The stick has new notes")
            .setContentText("Tap to sync them. (Set this app's battery use to Unrestricted to sync on its own.)")
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(4, nf)
    }
}
