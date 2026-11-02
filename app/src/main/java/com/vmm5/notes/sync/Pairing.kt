package com.vmm5.notes.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vmm5.notes.R
import com.vmm5.notes.ble.StickClient
import com.vmm5.notes.data.Db
import com.vmm5.notes.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom

/**
 * One stick, one phone (firmware 2.14+), without codes:
 *
 *  - The app has a random phone id, made on first use.
 *  - It syncs only with its own stick (by the stick's id), or with a stick
 *    that belongs to no phone. Another phone's stick is never touched, and
 *    is never bothered: the app does not even connect to it.
 *  - The first time it meets a stick that belongs to no phone, it asks:
 *    "Found a stick nearby. Use it with this phone?" Yes claims it (the
 *    stick remembers this phone); "Not mine" ignores that stick from then on.
 *  - To move a stick to this phone from another, choose it in Settings →
 *    Stick → Change; the stick asks on its own screen (KEY1 allow).
 *  - The stick forgets its phone from its Settings (Phone → KEY1 twice).
 *
 * Kept in SharedPreferences (the wake-up filter reads it synchronously).
 * Sticks before 2.14 have no id: the app syncs with them as before.
 */
object Pairing {
    private const val PREFS = "pairing"

    /** The stick id this phone syncs with, or null. */
    val paired = MutableStateFlow<Int?>(null)
    /** A stick that belongs to no phone, waiting for the user's yes or no. */
    val candidate = MutableStateFlow<Int?>(null)
    /** What the stick said to a claim, for the Settings screen. */
    val status = MutableStateFlow<String?>(null)

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        paired.value = sp(c).getInt("paired", -1).takeIf { it >= 0 }
    }

    fun phoneId(c: Context): String {
        sp(c).getString("phone", null)?.let { return it }
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("phone", id).apply()
        return id
    }

    fun pairedId(c: Context): Int? = sp(c).getInt("paired", -1).takeIf { it >= 0 }
    private fun approvedId(c: Context): Int? = sp(c).getInt("approved", -1).takeIf { it >= 0 }
    private fun ignored(c: Context): Set<Int> = sp(c).getStringSet("ignored", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    private fun setPaired(c: Context, id: Int?) {
        sp(c).edit().apply { if (id == null) remove("paired") else putInt("paired", id) }.remove("approved").apply()
        paired.value = id
        if (!SyncService.running.value) StickWatcher.register(c) // the wake-up filter follows the paired stick (the service re-registers when it stops)
    }

    /** Which sticks the app may connect to on its own (or for an update). */
    fun accepts(c: Context, ad: StickClient.StickAd): Boolean {
        val mine = pairedId(c)
        val id = ad.id ?: return mine == null           // older firmware: only while nothing is paired
        if (mine != null) return id == mine || id == approvedId(c)
        return id == approvedId(c) || (!ad.owned && id !in ignored(c))
    }

    /** The user said yes to a stick (the question, or Settings → Change): claim it on the next connection. */
    fun approve(c: Context, id: Int) {
        sp(c).edit().putInt("approved", id).apply()
        sp(c).edit().putStringSet("ignored", ignored(c).minus(id).map { it.toString() }.toSet()).apply()
        candidate.value = null
        status.value = "Connecting to stick %04X…".format(id)
        cancelNotification(c)
        SyncService.start(c, syncNow = true)
    }

    /** "Not mine": leave this stick alone from now on. */
    fun reject(c: Context, id: Int) {
        sp(c).edit().putStringSet("ignored", ignored(c).plus(id).map { it.toString() }.toSet()).apply()
        candidate.value = null
        cancelNotification(c)
    }

    fun unpair(c: Context) = setPaired(c, null)

    enum class Outcome { Ours, NotOurs }

    /**
     * Right after connecting and reading the version: says hello, claims if
     * the user approved this stick, and works out whether to go on.
     */
    suspend fun handshake(c: Context, client: StickClient, ad: StickClient.StickAd, version: String?): Outcome {
        val id = ad.id
        if (id == null || !firmwareAtLeast(version, 2, 14)) return Outcome.Ours // before pairing existed
        val db = Db.get(c)
        val claim = id == approvedId(c)
        if (claim && ad.owned) {
            status.value = "Press KEY1 on stick %04X to allow this phone".format(id)
            Syncer.state.value = Syncer.state.value.copy(detail = status.value!!)
        }
        val answer = client.hello(phoneId(c), claim)
        val label = "%04X".format(id)
        return when (answer) {
            "ok" -> {
                if (pairedId(c) != id) {
                    setPaired(c, id)
                    db.log("sync", "This phone is now paired with stick $label")
                    status.value = "Paired with stick $label"
                }
                Outcome.Ours
            }
            "new" -> {
                // A stick with no phone. If it was ours, it has forgotten this phone.
                if (pairedId(c) == id) {
                    setPaired(c, null)
                    db.log("sync", "Stick $label no longer has a phone (forgotten on the stick)")
                }
                ask(c, id)
                Outcome.NotOurs
            }
            "other" -> {
                if (pairedId(c) == id) {
                    setPaired(c, null)
                    db.log("sync", "Stick $label now belongs to another phone")
                }
                Outcome.NotOurs
            }
            "denied" -> {
                sp(c).edit().remove("approved").apply()
                db.log("sync", "Stick $label said no to this phone")
                status.value = "Stick $label said no"
                Outcome.NotOurs
            }
            else -> Outcome.Ours // no answer to hello: older firmware
        }
    }

    /** Puts the question to the user: in the app if it is open, and as a notification. */
    private fun ask(c: Context, id: Int) {
        if (candidate.value == id) return
        candidate.value = id
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Notifier.channels(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nf = NotificationCompat.Builder(c, Notifier.CHANNEL_NOTES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Found a stick nearby")
            .setContentText("Open the app to use it with this phone.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(5, nf)
    }

    private fun cancelNotification(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(5)
}
