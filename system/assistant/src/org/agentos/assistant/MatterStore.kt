package org.agentos.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** CE storage. Runtime transactions commit observations, rules and unique delivery fences together. */
internal class MatterStore(context: Context) : SQLiteOpenHelper(context, "matters.db", null, 2), MatterRetention.Storage {
    private val history: MatterReceiptHistory

    init {
        setWriteAheadLoggingEnabled(true)
        history = MatterReceiptHistory(File(context.filesDir, "agent-ui-receipts"))
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.setMaximumSize(32L * 1024 * 1024)
        db.rawQuery("PRAGMA journal_size_limit=4194304", null).use { cursor -> cursor.moveToFirst() }
        archiveCardViewReceipts(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE matters(id TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE bindings(matter TEXT NOT NULL, source TEXT NOT NULL, block TEXT NOT NULL, PRIMARY KEY(matter,block))")
        db.execSQL("CREATE TABLE rules(id TEXT PRIMARY KEY, matter TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE occurrences(id TEXT PRIMARY KEY, rule TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY, matter TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        createPanels(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createPanels(db)
        else throw IllegalStateException("Unsupported matter schema $oldVersion to $newVersion")
    }


    private fun createPanels(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE panel_records(id TEXT PRIMARY KEY, matter TEXT NOT NULL UNIQUE, position INTEGER NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX panel_order ON panel_records(position,id)")
        db.execSQL("CREATE TABLE panel_updates(id TEXT PRIMARY KEY, task TEXT UNIQUE, panel TEXT NOT NULL, settled INTEGER NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX panel_update_owner ON panel_updates(panel)")
        db.execSQL("CREATE INDEX panel_update_pending ON panel_updates(settled)")
        for (table in arrayOf("panel_directory", "panel_receipts", "card_view_states", "card_view_receipts", "primary_snapshots", "card_state_migrations", "display_supersessions"))
            db.execSQL("CREATE TABLE $table(id TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        val directory = JSONObject().put("listRevision", 0).put("cursorSecret", java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID())
        db.execSQL("INSERT INTO panel_directory(id,json,updated) VALUES('directory',?,?)", arrayOf<Any>(directory.toString(), System.currentTimeMillis()))
    }

    fun interface Work<T> {
        @Throws(Exception::class)
        fun run(): T
    }

    @Throws(Exception::class)
    fun <T> transaction(work: Work<T>): T {
        val db = writableDatabase
        if (!db.inTransaction()) archiveCardViewReceipts(db)
        db.beginTransaction()
        try {
            val result = work.run()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    @Throws(Exception::class)
    fun get(table: String, id: String): JSONObject? {
        readableDatabase.rawQuery("SELECT json FROM $table WHERE id=?", arrayOf(id)).use { cursor ->
            return if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
        }
    }

    @Throws(Exception::class)
    fun commandReceipt(id: String): JSONObject? {
        val live = get("commands", id)
        return if (live == null) history.command(id) else live
    }

    fun cardViewReceipt(id: String): JSONObject? = get("card_view_receipts", id) ?: history.cardView(id)

    fun hasCardViewReceipt(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM card_view_receipts WHERE id=?", arrayOf(id)).use {
        it.moveToFirst()
    } || history.hasCardView(id)

    private fun archiveCardViewReceipts(db: SQLiteDatabase) {
        check(!db.inTransaction())
        // Only committed rows can become archive fences. New state and its receipt commit together.
        while (true) {
            val entry = db.rawQuery("SELECT id,json FROM card_view_receipts LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0) to JSONObject(it.getString(1)) else null
            } ?: return
            history.archiveCardView(entry.first, entry.second)
            db.delete("card_view_receipts", "id=?", arrayOf(entry.first))
        }
    }

    @Throws(Exception::class)
    override fun all(table: String): JSONArray {
        val result = JSONArray()
        val order = if (table == "matters" || table == "sources") " ORDER BY updated,id" else ""
        readableDatabase.rawQuery("SELECT json FROM $table$order", null).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }

    @Throws(Exception::class)
    fun related(table: String, field: String, id: String): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT json FROM $table WHERE $field=?", arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }

    @Throws(Exception::class)
    fun put(table: String, id: String, value: JSONObject) {
        val limit = if (table == "matters") MAX_MATTERS else if (table == "sources") MAX_SOURCES else Int.MAX_VALUE
        if (get(table, id) == null && count(table) >= limit) throw IOException("MATTER_STORAGE_FULL")
        val text = value.toString()
        val maximum = when (table) { "primary_snapshots", "panel_updates", "card_view_states", "card_view_receipts" -> 8 * 1024 * 1024; else -> 128_000 }
        if (text.toByteArray(StandardCharsets.UTF_8).size > maximum) throw IOException("MATTER_TOO_LARGE")
        if (table == "panel_records") writableDatabase.execSQL("INSERT INTO panel_records(id,matter,position,json,updated) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET matter=excluded.matter,position=excluded.position,json=excluded.json,updated=excluded.updated",
            arrayOf<Any>(id, value.getString("matterId"), value.getLong("position"), text, System.currentTimeMillis()))
        else if (table == "panel_updates") writableDatabase.execSQL("INSERT INTO panel_updates(id,task,panel,settled,json,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET task=excluded.task,panel=excluded.panel,settled=excluded.settled,json=excluded.json,updated=excluded.updated",
            arrayOf<Any?>(id, value.optString("taskId").takeIf { it.isNotEmpty() }, value.getString("panelId"), if (value.optBoolean("settled")) 1 else 0, text, System.currentTimeMillis()))
        else if (table == "rules") writableDatabase.execSQL("INSERT OR REPLACE INTO rules(id,matter,json) VALUES(?,?,?)", arrayOf(id, value.getString("matterId"), text))
        else if (table == "commands" || table == "occurrences") {
            val field = if (table == "commands") "matter" else "rule"
            writableDatabase.execSQL("INSERT OR REPLACE INTO $table(id,$field,json,updated) VALUES(?,?,?,?)",
                arrayOf<Any>(id, value.getString(if (table == "commands") "matterId" else "ruleId"), text, System.currentTimeMillis()))
        } else writableDatabase.execSQL("INSERT OR REPLACE INTO $table(id,json,updated) VALUES(?,?,?)", arrayOf<Any>(id, text, System.currentTimeMillis()))
    }

    fun count(table: String): Long {
        readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    fun remove(table: String, id: String) { writableDatabase.delete(table, "id=?", arrayOf(id)) }

    fun hasPendingPanelUpdates(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM panel_updates WHERE settled=0 LIMIT 1", null).use { it.moveToFirst() }

    fun pendingPanelUpdates(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT json FROM panel_updates WHERE settled=0", null).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }

    fun panelUpdateForTask(task: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM panel_updates WHERE task=? AND settled=0", arrayOf(task)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun latestPanelUpdate(panel: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM panel_updates WHERE panel=? ORDER BY rowid DESC LIMIT 1", arrayOf(panel)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun panelForMatter(id: String): JSONObject? {
        readableDatabase.rawQuery("SELECT json FROM panel_records WHERE matter=?", arrayOf(id)).use {
            return if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }
    }

    fun panelPage(position: Long, id: String, limit: Int): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT json FROM panel_records WHERE position>? OR (position=? AND id>?) ORDER BY position,id LIMIT ?",
            arrayOf(position.toString(), position.toString(), id, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }

    fun bind(matter: String, source: String, block: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO bindings(matter,source,block) VALUES(?,?,?)", arrayOf(matter, source, block))
    }

    @Throws(Exception::class)
    fun bindings(column: String, id: String): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT matter,source,block FROM bindings WHERE $column=?", arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject().put("matterId", cursor.getString(0)).put("sourceId", cursor.getString(1)).put("blockId", cursor.getString(2)))
        }
        return result
    }

    @Throws(Exception::class)
    override fun allBindings(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT matter,source,block FROM bindings", null).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject().put("matterId", cursor.getString(0)).put("sourceId", cursor.getString(1)).put("blockId", cursor.getString(2)))
        }
        return result
    }

    @Throws(Exception::class)
    override fun removeMatter(id: String) {
        val commands = related("commands", "matter", id)
        for (i in 0 until commands.length()) archiveCommand(commands.getJSONObject(i))
        writableDatabase.delete("bindings", "matter=?", arrayOf(id))
        writableDatabase.delete("matters", "id=?", arrayOf(id))
        remove("primary_snapshots", id)
        remove("card_view_states", id)
    }

    override fun removeSource(id: String) {
        writableDatabase.delete("sources", "id=?", arrayOf(id))
    }

    @Throws(Exception::class)
    fun archiveCommand(command: JSONObject) {
        // Write the receipt before deleting its live row, so crashes always retain a duplicate fence.
        history.archiveCommand(command)
        writableDatabase.delete("commands", "id=?", arrayOf(command.getString("id")))
    }

    @Throws(Exception::class)
    fun prune() {
        // Active rules, source observations and unresolved receipts are never evicted as history.
        for (table in arrayOf("occurrences")) {
            val records = all(table)
            if (records.length() < 512) continue
            val before = System.currentTimeMillis() - 30L * 86400_000
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.optBoolean("terminal") && record.optLong("createdAt") < before)
                    writableDatabase.delete(table, "id=?", arrayOf(record.getString("id")))
            }
        }
    }

    companion object {
        const val MAX_MATTERS = 256
        const val MAX_SOURCES = 256
        const val MAX_RULES = 64
        const val MAX_COMMANDS = 64
        const val MAX_COMMAND_HISTORY = 512
    }
}
