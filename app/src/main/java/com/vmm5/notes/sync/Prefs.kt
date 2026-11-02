package com.vmm5.notes.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Prefs(private val context: Context) {
    companion object {
        val DELETE_AFTER = booleanPreferencesKey("delete_after_pull")
        val BACKGROUND = booleanPreferencesKey("background_sync")
        val MODEL = stringPreferencesKey("whisper_model")
        val SCAN_ALWAYS = booleanPreferencesKey("scan_always")
        const val SCAN_WINDOW_MS = 10 * 60_000L
        val BATTERY = intPreferencesKey("stick_battery")
        val BATTERY_MV = intPreferencesKey("stick_battery_mv")
        val BATTERY_AT = longPreferencesKey("stick_battery_at")
        val CHARGE = stringPreferencesKey("stick_charge")
        val LIST_ENABLED = booleanPreferencesKey("list_enabled")
        val LIST_PHRASE = stringPreferencesKey("list_phrase")
        const val DEFAULT_LIST_PHRASE = "add to list"
        val PHONE_COUNTER = intPreferencesKey("phone_counter")
        val PHONE_TAG = stringPreferencesKey("phone_tag")
        const val DEFAULT_MODEL = "base.en"
        val JOURNAL_DIR = stringPreferencesKey("journal_dir")
        val LOCATION = booleanPreferencesKey("note_location")
        val STICK_VERSION = stringPreferencesKey("stick_version")
        // Notifications (0.14).
        val NOTIFY_SYNCED = booleanPreferencesKey("notify_synced")
        val NOTIFY_MAX_PER_HOUR = intPreferencesKey("notify_max_per_hour")
        val NOTIFY_BATTERY = booleanPreferencesKey("notify_battery")
        val QUIET_STATUS = booleanPreferencesKey("quiet_status")
        // The stick's own settings (firmware 2.12+): what it last reported,
        // and what the user chose here and has not been sent yet (absent = no change).
        val STICK_THEME = intPreferencesKey("stick_theme")
        val STICK_BRIGHT = intPreferencesKey("stick_bright")
        val STICK_FMT = intPreferencesKey("stick_fmt")
        val WANT_THEME = intPreferencesKey("want_theme")
        val WANT_BRIGHT = intPreferencesKey("want_bright")
        val WANT_FMT = intPreferencesKey("want_fmt")
        val APP_STYLE = intPreferencesKey("app_style") // 0 Tango, 1 Minimal
        val STICK_THEMES = listOf("Tango", "Plain", "Wireframe", "Warm", "Pixel", "Minimal", "Tape")
        val STICK_BRIGHTNESS = listOf("Bright", "Medium", "Low")
        val STICK_FORMATS = listOf("Opus 16 kHz (small)", "WAV 8 kHz", "WAV 16 kHz")
        val CLEANUP = booleanPreferencesKey("cleanup_enabled")
        val CLEANUP_PROMPT = stringPreferencesKey("cleanup_prompt")
        val CLEANED_IN_JOURNAL = booleanPreferencesKey("cleaned_in_journal")
        const val DEFAULT_CLEANUP_PROMPT = "Rewrite this voice note as short, clear bullet points. Keep every idea and detail that was said; " +
            "remove filler, repetition and false starts. Do not add anything that was not said. Use plain language. " +
            "If there is a to-do or decision, put it on its own line starting with \"Action:\". Output only the bullet points."
    }

    val deleteAfterPull: Flow<Boolean> = context.store.data.map { it[DELETE_AFTER] ?: true }
    val backgroundSync: Flow<Boolean> = context.store.data.map { it[BACKGROUND] ?: true }
    val model: Flow<String> = context.store.data.map { it[MODEL] ?: DEFAULT_MODEL }
    /** false (default): look for the stick for 10 minutes after the app is opened, a sync is asked for, or a pull; then stop. */
    val scanAlways: Flow<Boolean> = context.store.data.map { it[SCAN_ALWAYS] ?: false }
    suspend fun scanAlways() = scanAlways.first()
    suspend fun setScanAlways(v: Boolean) = context.store.edit { it[SCAN_ALWAYS] = v }

    suspend fun deleteAfterPull() = deleteAfterPull.first()
    suspend fun backgroundSync() = backgroundSync.first()
    suspend fun model() = model.first()

    suspend fun setDeleteAfterPull(v: Boolean) = context.store.edit { it[DELETE_AFTER] = v }
    suspend fun setBackgroundSync(v: Boolean) = context.store.edit { it[BACKGROUND] = v }
    suspend fun setModel(v: String) = context.store.edit { it[MODEL] = v }

    /** A folder the user picked (Drive, Downloads, ...) that gets a copy of musings.md after every entry; null for none. */
    val journalDir: Flow<String?> = context.store.data.map { it[JOURNAL_DIR] }
    suspend fun journalDir() = journalDir.first()
    suspend fun setJournalDir(v: String?) = context.store.edit { if (v == null) it.remove(JOURNAL_DIR) else it[JOURNAL_DIR] = v }

    /** The stick's firmware version as last reported ("2.6.2" or older firmware: "before 2.7.0"). */
    val stickVersion: Flow<String?> = context.store.data.map { it[STICK_VERSION] }
    suspend fun setStickVersion(v: String) = context.store.edit { it[STICK_VERSION] = v }

    /** Note where the phone was when each note is pulled (needs the location permission). */
    val noteLocation: Flow<Boolean> = context.store.data.map { it[LOCATION] ?: false }
    suspend fun noteLocation() = noteLocation.first()
    suspend fun setNoteLocation(v: Boolean) = context.store.edit { it[LOCATION] = v }

    /** Tidy each transcript with the on-device model after transcription. */
    val cleanup: Flow<Boolean> = context.store.data.map { it[CLEANUP] ?: false }
    suspend fun cleanup() = cleanup.first()
    suspend fun setCleanup(v: Boolean) = context.store.edit { it[CLEANUP] = v }
    val cleanupPrompt: Flow<String> = context.store.data.map { it[CLEANUP_PROMPT] ?: DEFAULT_CLEANUP_PROMPT }
    suspend fun cleanupPrompt() = cleanupPrompt.first()
    suspend fun setCleanupPrompt(v: String) = context.store.edit { if (v.isBlank()) it.remove(CLEANUP_PROMPT) else it[CLEANUP_PROMPT] = v }
    /** Journals and shares carry the cleaned text when there is one. */
    val cleanedInJournal: Flow<Boolean> = context.store.data.map { it[CLEANED_IN_JOURNAL] ?: true }
    suspend fun cleanedInJournal() = cleanedInJournal.first()
    suspend fun setCleanedInJournal(v: Boolean) = context.store.edit { it[CLEANED_IN_JOURNAL] = v }

    /** The stick's battery as last reported: (percent, millivolts, when), or null. */
    val stickBattery: Flow<Triple<Int, Int, Long>?> = context.store.data.map { p ->
        val pct = p[BATTERY] ?: return@map null
        Triple(pct, p[BATTERY_MV] ?: 0, p[BATTERY_AT] ?: 0L)
    }
    suspend fun stickBattery() = stickBattery.first()
    /** While plugged in the percentage is frozen at its last unplugged value (the charger distorts it). */
    suspend fun setStickBattery(pct: Int, mv: Int, charge: String = "bat") = context.store.edit {
        if (charge == "bat" || it[BATTERY] == null) it[BATTERY] = pct
        it[BATTERY_MV] = mv; it[BATTERY_AT] = System.currentTimeMillis(); it[CHARGE] = charge
    }
    val stickCharge: Flow<String> = context.store.data.map { it[CHARGE] ?: "bat" }

    /** The code-phrase list: "add to list, buy stamps" becomes a checkbox line in musings-list.md. */
    val listEnabled: Flow<Boolean> = context.store.data.map { it[LIST_ENABLED] ?: true }
    suspend fun listEnabled() = listEnabled.first()
    suspend fun setListEnabled(v: Boolean) = context.store.edit { it[LIST_ENABLED] = v }
    val listPhrase: Flow<String> = context.store.data.map { it[LIST_PHRASE] ?: DEFAULT_LIST_PHRASE }
    suspend fun listPhrase() = listPhrase.first()
    suspend fun setListPhrase(v: String) = context.store.edit { if (v.isBlank()) it.remove(LIST_PHRASE) else it[LIST_PHRASE] = v.trim() }

    /** Notes recorded on the phone: their own counter and last tag. */
    suspend fun nextPhoneNumber(): Int { var n = 0; context.store.edit { n = (it[PHONE_COUNTER] ?: 0) + 1; it[PHONE_COUNTER] = n }; return n }
    val phoneTag: Flow<String> = context.store.data.map { it[PHONE_TAG] ?: "rec" }
    suspend fun setPhoneTag(v: String) = context.store.edit { it[PHONE_TAG] = v }

    /** A notification when notes have been synced from the stick (the only one on by default). */
    val notifySynced: Flow<Boolean> = context.store.data.map { it[NOTIFY_SYNCED] ?: true }
    suspend fun notifySynced() = notifySynced.first()
    suspend fun setNotifySynced(v: Boolean) = context.store.edit { it[NOTIFY_SYNCED] = v }
    /** How many times an hour the synced notification may sound; past that it updates quietly. */
    val notifyMaxPerHour: Flow<Int> = context.store.data.map { it[NOTIFY_MAX_PER_HOUR] ?: 3 }
    suspend fun notifyMaxPerHour() = notifyMaxPerHour.first()
    suspend fun setNotifyMaxPerHour(v: Int) = context.store.edit { it[NOTIFY_MAX_PER_HOUR] = v }
    /** A notification when the stick's battery is low (off by default). */
    val notifyBattery: Flow<Boolean> = context.store.data.map { it[NOTIFY_BATTERY] ?: false }
    suspend fun notifyBattery() = notifyBattery.first()
    suspend fun setNotifyBattery(v: Boolean) = context.store.edit { it[NOTIFY_BATTERY] = v }
    /** The "syncing" / "transcribing" status Android requires while working: minimised (no status bar icon) when true. */
    val quietStatus: Flow<Boolean> = context.store.data.map { it[QUIET_STATUS] ?: true }
    suspend fun quietStatus() = quietStatus.first()
    suspend fun setQuietStatus(v: Boolean) = context.store.edit { it[QUIET_STATUS] = v }

    data class StickSettings(val theme: Int?, val bright: Int?, val fmt: Int?)
    /** What the stick reported on the last sync (nulls before firmware 2.12). */
    val stickSettings: Flow<StickSettings> = context.store.data.map { StickSettings(it[STICK_THEME], it[STICK_BRIGHT], it[STICK_FMT]) }
    suspend fun setStickSettings(theme: Int, bright: Int, fmt: Int) = context.store.edit {
        it[STICK_THEME] = theme; it[STICK_BRIGHT] = bright; it[STICK_FMT] = fmt
    }
    /** Changes waiting to be sent to the stick on the next connection. */
    val wantedStickSettings: Flow<StickSettings> = context.store.data.map { StickSettings(it[WANT_THEME], it[WANT_BRIGHT], it[WANT_FMT]) }
    suspend fun wantedStickSettings() = wantedStickSettings.first()
    suspend fun wantStick(theme: Int? = null, bright: Int? = null, fmt: Int? = null) = context.store.edit {
        theme?.let { v -> it[WANT_THEME] = v }; bright?.let { v -> it[WANT_BRIGHT] = v }; fmt?.let { v -> it[WANT_FMT] = v }
    }
    suspend fun clearWantedStick() = context.store.edit { it.remove(WANT_THEME); it.remove(WANT_BRIGHT); it.remove(WANT_FMT) }
    /** The app's look: 0 Tango (the cat, warm colours), 1 Minimal (no cat, neutral greys). */
    val appStyle: Flow<Int> = context.store.data.map { it[APP_STYLE] ?: 0 }
    suspend fun setAppStyle(v: Int) = context.store.edit { it[APP_STYLE] = v }

    /** Every setting as "key = value" lines, for the diagnostic log. */
    suspend fun dump(): String = context.store.data.first().asMap().entries
        .sortedBy { it.key.name }.joinToString("\n") { "${it.key.name} = ${it.value}" }
}

/** Compares "2.13.0"-style versions; unreadable parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val x = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** "2.12.0" >= major.minor? Anything unreadable ("before 2.7.0") is older. */
fun firmwareAtLeast(v: String?, major: Int, minor: Int): Boolean {
    val p = v?.trim()?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
    if (p.size < 2) return false
    return p[0] > major || (p[0] == major && p[1] >= minor)
}
