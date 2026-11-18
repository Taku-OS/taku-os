package org.agentos.agent.home.feed

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID

internal data class FeedPreferences(
  val removed: Set<String> = emptySet(),
  val pins: List<String> = emptyList(),
  val defaultsApplied: Boolean = false,
) {
  /** Published cards are durable and unbounded; never evict a user's dismissal by age/count. */
  fun removing(id: String) = copy(removed = removed + id, pins = pins.filterNot { it == id })

  fun json() = JSONObject().put("removed", JSONArray(removed.toList())).put("pins", JSONArray(pins))
    .put("defaultsApplied", defaultsApplied)

  companion object {
    fun parse(json: JSONObject): FeedPreferences {
      fun ids(key: String): List<String> {
        val array = json.getJSONArray(key)
        return (0 until array.length()).map { index ->
          val id = array.get(index) as? String ?: throw IllegalArgumentException("invalid stored $key")
          require(FeedCard.isValidId(id)) { "invalid stored $key" }
          id
        }
      }
      val defaults = json.get("defaultsApplied") as? Boolean ?: throw IllegalArgumentException("invalid stored defaultsApplied")
      return FeedPreferences(LinkedHashSet(ids("removed")), ids("pins"), defaults)
    }
  }
}

internal sealed interface FeedCardWrite {
  data class Put(val card: FeedCard) : FeedCardWrite
  data class Retract(val id: String) : FeedCardWrite
  data object Reset : FeedCardWrite
}

internal data class StoredFeedEvent(val sequence: Long, val byteCount: Long)

internal class FeedStore(context: Context) : SQLiteOpenHelper(
  context, "home-feed.db", null, 1,
  DatabaseErrorHandler { throw SQLiteException("Feed database is corrupt; preserving it for recovery") },
) {
  private val legacyFile = File(context.filesDir, "home-feed/state.json")
  lateinit var epoch: UUID
    private set
  var legacyImportedCount = 0L
    private set
  var legacyHistoryMayBeIncomplete = false
    private set

  init { setWriteAheadLoggingEnabled(true) }

  override fun onConfigure(db: SQLiteDatabase) {
    db.execSQL("PRAGMA synchronous=FULL")
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE feed_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execSQL("CREATE TABLE published_cards (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, json TEXT NOT NULL)")
    db.execSQL("""CREATE TABLE feed_events (
      sequence INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, origin TEXT, op TEXT, card_id TEXT,
      action_id TEXT, json BLOB NOT NULL, byte_count INTEGER NOT NULL)""")
    db.execSQL("CREATE INDEX feed_events_card_sequence ON feed_events(card_id, sequence)")
    db.execSQL("CREATE INDEX feed_events_origin_op_sequence ON feed_events(origin, op, sequence)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    throw SQLiteException("Unsupported Feed database version $oldVersion")
  }

  fun restore(published: MutableMap<String, FeedCard>): FeedPreferences {
    try {
      val db = writableDatabase
      if (meta(db, "migration_complete") != "1") migrate(db)
      epoch = UUID.fromString(requiredMeta(db, "database_epoch"))
      legacyImportedCount = requiredMeta(db, "legacy_imported_count").toLong().also { require(it >= 0) }
      legacyHistoryMayBeIncomplete = when (requiredMeta(db, "legacy_history_may_be_incomplete")) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("invalid migration history marker")
      }
      val preferences = FeedPreferences.parse(JSONObject(requiredMeta(db, "preferences")))
      db.rawQuery("SELECT id, json FROM published_cards ORDER BY ordinal", null).use { cursor ->
        while (cursor.moveToNext()) {
          val card = FeedCard.parse(JSONObject(cursor.getString(1)), forcedSource = "agent")
          require(card.id == cursor.getString(0)) { "stored card identity mismatch" }
          published[card.id] = card
        }
      }
      return preferences
    } catch (e: FeedException) {
      if (e.code == "STATE_WRITE_FAILED") throw e
      throw FeedException("STATE_READ_FAILED", "Could not restore Feed state")
    } catch (_: Exception) {
      throw FeedException("STATE_READ_FAILED", "Could not restore Feed state")
    }
  }

  fun head(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(sequence), 0) FROM feed_events", null).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  fun commit(event: FeedEvent, preferences: FeedPreferences? = null, cards: FeedCardWrite? = null): FeedCursor {
    try {
      val db = writableDatabase
      val sequence: Long
      db.beginTransaction()
      try {
        when (cards) {
          is FeedCardWrite.Put -> putCard(db, cards.card)
          is FeedCardWrite.Retract -> db.delete("published_cards", "id = ?", arrayOf(cards.id))
          FeedCardWrite.Reset -> db.delete("published_cards", null, null)
          null -> Unit
        }
        if (preferences != null) putMeta(db, "preferences", preferences.json().toString())
        sequence = append(db, event, epoch)
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
      return FeedCursor(epoch, sequence)
    } catch (e: FeedException) {
      throw e
    } catch (_: Exception) {
      throw FeedException("STATE_WRITE_FAILED", "Could not commit Feed state and event")
    }
  }

  fun applyDefaults(preferences: FeedPreferences) {
    try {
      val db = writableDatabase
      db.beginTransaction()
      try {
        putMeta(db, "preferences", preferences.json().toString())
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    } catch (_: Exception) {
      throw FeedException("STATE_WRITE_FAILED", "Could not commit default Feed pins")
    }
  }

  fun events(after: Long, through: Long, filter: FeedEventFilter, cancellation: CancellationSignal): List<StoredFeedEvent> {
    val where = StringBuilder("sequence > ? AND sequence <= ?")
    val args = arrayListOf(after.toString(), through.toString())
    when (val cards = filter.cards) {
      FeedCardFilter.All -> Unit
      is FeedCardFilter.Ids -> {
        where.append(" AND card_id IN (").append(cards.ids.joinToString(",") { "?" }).append(')')
        args.addAll(cards.ids)
      }
      is FeedCardFilter.Prefix -> {
        where.append(" AND substr(card_id, 1, length(?)) = ? COLLATE BINARY")
        args.add(cards.prefix)
        args.add(cards.prefix)
      }
    }
    filter.origin?.let { where.append(" AND origin = ?"); args.add(it.wire) }
    filter.operation?.let { where.append(" AND op = ?"); args.add(it.wire) }
    try {
      return readableDatabase.rawQuery(
        "SELECT sequence, byte_count FROM feed_events WHERE $where ORDER BY sequence LIMIT 64", args.toTypedArray(), cancellation,
      ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(StoredFeedEvent(cursor.getLong(0), cursor.getLong(1))) }
      }
    } catch (_: Exception) {
      throw FeedException("STATE_READ_FAILED", "Could not read Feed events")
    }
  }

  fun chunk(sequence: Long, offset: Long, cancellation: CancellationSignal): ByteArray {
    try {
      return readableDatabase.rawQuery("SELECT substr(json, ?, 16384) FROM feed_events WHERE sequence = ?",
        arrayOf((offset + 1).toString(), sequence.toString()), cancellation).use { cursor ->
        if (!cursor.moveToFirst()) throw SQLiteException("missing Feed event")
        cursor.getBlob(0)
      }
    } catch (_: Exception) {
      throw FeedException("STATE_READ_FAILED", "Could not read Feed event bytes")
    }
  }

  private fun migrate(db: SQLiteDatabase) {
    // AtomicFile's backup is the last committed version. Read it without renaming or deleting migration input.
    val backup = File(legacyFile.path + ".bak")
    val source = when {
      backup.exists() -> backup
      legacyFile.exists() -> legacyFile
      File(legacyFile.path + ".new").exists() -> throw FeedException("STATE_READ_FAILED", "Feed legacy state has no committed version")
      else -> null
    }
    val legacy = try {
      source?.let {
        val tokens = JSONTokener(it.readText(Charsets.UTF_8))
        val state = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException("invalid Feed legacy state")
        require(tokens.nextClean() == '\u0000') { "trailing Feed legacy state data" }
        require(state.get("version") == 1) { "unsupported Feed legacy state version" }
        state
      }
    } catch (_: Exception) {
      throw FeedException("STATE_READ_FAILED", "Could not read Feed legacy state")
    }
    val preferences: FeedPreferences
    val cards: List<FeedCard>
    val actions: JSONArray
    try {
      preferences = legacy?.let { FeedPreferences.parse(it) } ?: FeedPreferences()
      cards = legacy?.getJSONArray("published")?.let { array ->
        (0 until array.length()).map { FeedCard.parse(array.getJSONObject(it), forcedSource = "agent") }
      } ?: emptyList()
      actions = legacy?.getJSONArray("actions") ?: JSONArray()
      for (index in 0 until actions.length()) actions.getJSONObject(index)
    } catch (_: Exception) {
      throw FeedException("STATE_READ_FAILED", "Feed legacy state is invalid")
    }
    val importedEpoch = UUID.randomUUID()
    try {
      db.beginTransaction()
      try {
        for (card in cards) putCard(db, card)
        for (index in 0 until actions.length()) append(db, FeedEvent.legacy(actions.getJSONObject(index)), importedEpoch)
        putMeta(db, "database_epoch", importedEpoch.toString())
        putMeta(db, "preferences", preferences.json().toString())
        putMeta(db, "legacy_imported_count", actions.length().toString())
        putMeta(db, "legacy_history_may_be_incomplete", (source != null).toString())
        putMeta(db, "migration_complete", "1")
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    } catch (_: Exception) {
      throw FeedException("STATE_WRITE_FAILED", "Could not import Feed legacy state")
    }
  }

  private fun putCard(db: SQLiteDatabase, card: FeedCard) {
    val values = ContentValues().apply { put("json", card.json.toString()) }
    if (db.update("published_cards", values, "id = ?", arrayOf(card.id)) == 0) {
      values.put("id", card.id)
      db.insertOrThrow("published_cards", null, values)
    }
  }

  private fun append(db: SQLiteDatabase, event: FeedEvent, epoch: UUID): Long {
    val values = ContentValues().apply {
      put("at", event.indexTime())
      put("origin", event.indexString("origin"))
      put("op", event.indexString("op"))
      put("card_id", event.indexString("cardId"))
      put("action_id", event.indexString("actionId"))
      put("json", byteArrayOf())
      put("byte_count", 0)
    }
    val sequence = db.insertOrThrow("feed_events", null, values)
    val bytes = event.encode(FeedCursor(epoch, sequence))
    val body = ContentValues().apply { put("json", bytes); put("byte_count", bytes.size) }
    check(db.update("feed_events", body, "sequence = ?", arrayOf(sequence.toString())) == 1)
    return sequence
  }

  private fun meta(db: SQLiteDatabase, key: String): String? =
    db.rawQuery("SELECT value FROM feed_meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

  private fun requiredMeta(db: SQLiteDatabase, key: String): String = meta(db, key) ?: throw SQLiteException("missing Feed metadata")

  private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
    val values = ContentValues().apply { put("key", key); put("value", value) }
    db.insertWithOnConflict("feed_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
      if (it == -1L) throw SQLiteException("Could not write Feed metadata")
    }
  }
}
