package com.vmm5.notes.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * The data model borrows the Pebble app's split: a `transfers` table that
 * is the state machine of getting a file off the device, and a `notes`
 * table that is the content people look at. A transfer row is keyed by the
 * stick's file name (the stick's numbers never repeat), so a crash in the
 * middle of a pull leaves a row that the next pull either finishes or
 * fails and retries.
 */

enum class TransferStatus { Started, Saving, Completed, Failed, Discarded }

@Entity(tableName = "transfers")
data class Transfer(
    @PrimaryKey val deviceName: String,   // run-thought_0042.wav
    val number: Int,
    val tag: String,                      // run-thought
    val bytes: Long,
    val status: TransferStatus,
    val fileId: String? = null,           // the saved file, once Completed
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val deletedFromStick: Boolean = false,
)

enum class NoteStatus { Pending, Transcribing, Completed, Failed }

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: Int,
    val tag: String,
    val deviceName: String,
    val fileId: String,                   // 2026-10-25_0042_run-thought (no extension)
    val pulledAt: Long,                   // the date the stick cannot know
    val seconds: Int,
    val bytes: Long,
    val status: NoteStatus = NoteStatus.Pending,
    val transcript: String? = null,
    val model: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val sharedAt: Long? = null,
    val journaled: Boolean = false,      // appended to musings.md
    val title: String? = null,           // a name the user gave it; the tag label otherwise
    val transcribeMs: Long? = null,      // how long the last successful transcription took
    val transcribeStartedAt: Long? = null, // set while Transcribing, for the elapsed counter and the stuck check
    val lat: Double? = null,             // where the phone was when the note was pulled
    val lon: Double? = null,
    val place: String? = null,           // "Harvard Square, Cambridge" from the geocoder, when it answered
    val cleaned: String? = null,         // the transcript tidied by the on-device model
    val cleanedBy: String? = null,       // "Gemini Nano"
    val cleanMs: Long? = null,
    val chunks: String? = null,          // finished chunk texts while transcribing, "\u001F"-separated; resume point
    val progress: String? = null,        // "2 of 5" while transcribing
)

/** The text to put in journals and shares: the cleaned version when there is one and it is wanted. */
fun Note.bodyText(preferCleaned: Boolean): String? = if (preferCleaned && !cleaned.isNullOrBlank()) cleaned else transcript

/** "#42" for a stick note, "P7" for one recorded on the phone. */
fun Note.numberLabel(): String = if (deviceName.startsWith("phone-")) "P$number" else "#$number"

/** What a note is called: its own title, else the tag label. */
fun Note.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: Tags.label(tag)

/** What the app did, for the Activity screen: one line per thing. */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val kind: String,        // pulled, deleted_stick, transcribed, shared, deleted, error, sync
    val text: String,
    val noteId: Long? = null,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(e: Event)

    @Query("SELECT * FROM events ORDER BY at DESC LIMIT 300")
    fun recent(): Flow<List<Event>>

    @Query("SELECT * FROM events ORDER BY at DESC LIMIT :n")
    suspend fun latest(n: Int): List<Event>

    @Query("DELETE FROM events WHERE at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers WHERE deviceName = :name")
    suspend fun get(name: String): Transfer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Transfer)

    @Update
    suspend fun update(t: Transfer)

    @Query("SELECT * FROM transfers WHERE status IN ('Started','Saving') ORDER BY number")
    fun pending(): Flow<List<Transfer>>

    @Query("UPDATE transfers SET status = 'Failed', error = :error WHERE status IN ('Started','Saving')")
    suspend fun failOrphans(error: String = "interrupted")

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC LIMIT 500")
    suspend fun latest(): List<Transfer>

    @Query("SELECT deviceName FROM transfers WHERE status = 'Completed'")
    suspend fun completedNames(): List<String>

    @Query("UPDATE transfers SET deletedFromStick = 1 WHERE deviceName = :name")
    suspend fun markDeleted(name: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pulledAt DESC, number DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY pulledAt DESC, number DESC")
    suspend fun snapshot(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun one(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id IN (:ids) ORDER BY number")
    suspend fun byIds(ids: List<Long>): List<Note>

    @Query("SELECT * FROM notes WHERE deviceName = :name")
    suspend fun byDeviceName(name: String): Note?

    @Insert
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notes WHERE sharedAt IS NULL AND status = 'Completed' ORDER BY number")
    suspend fun unshared(): List<Note>

    @Query("UPDATE notes SET sharedAt = :at WHERE id IN (:ids)")
    suspend fun markShared(ids: List<Long>, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM notes WHERE status = 'Pending' OR (status = 'Failed' AND attempts < 3) ORDER BY number")
    suspend fun needingTranscription(): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE sharedAt IS NULL AND status = 'Completed'")
    fun unsharedCount(): Flow<Int>
}

/** v1 -> v2: the events table. Notes and transfers are untouched. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `noteId` INTEGER)")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `journaled` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `title` TEXT")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `transcribeMs` INTEGER")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `transcribeStartedAt` INTEGER")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        for (c in listOf("`lat` REAL", "`lon` REAL", "`place` TEXT", "`cleaned` TEXT", "`cleanedBy` TEXT", "`cleanMs` INTEGER"))
            db.execSQL("ALTER TABLE `notes` ADD COLUMN $c")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `chunks` TEXT")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `progress` TEXT")
    }
}

@Database(entities = [Transfer::class, Note::class, Event::class], version = 6, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun transfers(): TransferDao
    abstract fun notes(): NoteDao
    abstract fun events(): EventDao

    suspend fun log(kind: String, text: String, noteId: Long? = null) = events().insert(Event(kind = kind, text = text, noteId = noteId))

    /** One transaction: the transfer is done and the note exists, or neither. */
    @Transaction
    open suspend fun completeTransfer(t: Transfer, note: Note): Long {
        transfers().upsert(t.copy(status = TransferStatus.Completed, fileId = note.fileId, completedAt = System.currentTimeMillis(), error = null))
        return notes().insert(note)
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Db::class.java, "vmm5.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { instance = it }
        }
    }
}
