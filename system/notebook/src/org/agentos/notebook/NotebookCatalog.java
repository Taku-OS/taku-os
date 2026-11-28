package org.agentos.notebook;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.agentos.ai.NotebookSocialSource;

final class NotebookCatalog {
    private static final int VERSION = 6;
    private static final int FILING_EXAMPLES_PER_LIST = 2;
    private static final List<String> AUTOMATIC_SOURCE_PACKAGES = Arrays.stream(NotebookSocialSource.values())
            .flatMap(source -> source.packages().stream()).sorted().toList();
    private static final List<String> ACCOUNT_SOURCE_PACKAGES = Stream.concat(
            AUTOMATIC_SOURCE_PACKAGES.stream(), Stream.of("com.discord")).toList();
    private static final String PENDING_SOURCE_LOOKUP =
            "source_resolution=? AND source_url IS NULL";
    private static final String ENTRY_COLUMNS = """
            SELECT e.id,e.title,l.name,e.source_package,e.source_label,e.source_url,
                   e.captured_at,e.sha256,e.mime_type,e.size_bytes,e.summary,e.list_id,
                   e.description,e.saved_at,e.title_edited,e.list_edited,
                   e.source_resolution,e.source_attempts,e.source_zone,e.filing_pending,e.source_evidence
            FROM entries e JOIN lists l ON l.id=e.list_id
            """;
    private static final String LIST_COLUMNS = """
            SELECT l.id,l.name,l.rule,(SELECT COUNT(*) FROM entries e WHERE e.list_id=l.id AND e.filing_pending=0)
            FROM lists l
            """;

    @FunctionalInterface
    interface Importer {
        void importEntries(NotebookCatalog catalog) throws IOException;
    }

    private final SQLiteDatabase database;

    private NotebookCatalog(SQLiteDatabase database) {
        this.database = database;
    }

    static NotebookCatalog open(File directory, Importer importer) throws IOException {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(new File(directory, "catalog.db"),
                new SQLiteDatabase.OpenParams.Builder().addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY)
                        .setErrorHandler(failed -> Log.e("NotebookCatalog", "Catalog is corrupt"))
                        .setSynchronousMode("FULL").build());
        try {
            database.setForeignKeyConstraintsEnabled(true);
            NotebookCatalog catalog = new NotebookCatalog(database);
            catalog.initialize(importer);
            return catalog;
        } catch (IOException | RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    private void initialize(Importer importer) throws IOException {
        int version = database.getVersion();
        if (version == VERSION) return;
        if (version < 0 || version > VERSION) throw new IOException("NOTEBOOK_VERSION_UNSUPPORTED");
        database.beginTransaction();
        try {
            if (version == 0) createCatalog();
            if (version < 2) addManualFiling();
            if (version < 3) addSourceLookup();
            if (version < 5) addPendingFiling();
            if (version < 6) {
                database.execSQL("ALTER TABLE entries ADD COLUMN source_evidence TEXT NOT NULL DEFAULT ''");
            }
            if (version == 0) importer.importEntries(this);
            if (version < 4) initializeSourceLookups();
            if (version < 5) migrateDefaultLists();
            database.setVersion(VERSION);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private void createCatalog() {
        database.execSQL("""
                CREATE TABLE lists (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    name_key TEXT NOT NULL UNIQUE,
                    rule TEXT NOT NULL
                )
                """);
        database.execSQL("""
                CREATE TABLE entries (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    source_package TEXT NOT NULL,
                    source_label TEXT NOT NULL,
                    source_url TEXT,
                    captured_at INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    summary TEXT NOT NULL,
                    list_id TEXT NOT NULL REFERENCES lists(id),
                    description TEXT NOT NULL,
                    saved_at INTEGER NOT NULL,
                    title_edited INTEGER NOT NULL DEFAULT 0,
                    list_edited INTEGER NOT NULL DEFAULT 0
                )
                """);
        database.execSQL("CREATE INDEX entries_by_list ON entries(list_id)");
        for (var list : NotebookStore.DEFAULT_LISTS) {
            if (list.legacyName() != null) insertList(list.id(), list.legacyName(), "");
        }
    }

    private void addPendingFiling() {
        database.execSQL("ALTER TABLE entries ADD COLUMN filing_pending INTEGER NOT NULL DEFAULT 0");
        database.execSQL("""
                CREATE INDEX entries_pending_filing ON entries(saved_at,id)
                WHERE filing_pending=1
                """);
    }

    private void migrateDefaultLists() {
        for (var defaults : NotebookStore.DEFAULT_LISTS) {
            NotebookStore.SavedList existing = getList(defaults.id());
            NotebookStore.SavedList sameName = listNamed(defaults.name());
            if (existing == null) {
                if (sameName == null) insertList(defaults.id(), defaults.name(), defaults.rule());
            } else if (existing.name.equals(defaults.legacyName())
                    && (sameName == null || sameName.id.equals(existing.id))) {
                String rule = existing.rule.isEmpty() ? defaults.rule() : existing.rule;
                updateList(existing.id, defaults.name(), rule);
            }
        }
    }

    private void addManualFiling() {
        database.execSQL("ALTER TABLE entries ADD COLUMN filed_at INTEGER NOT NULL DEFAULT 0");
        database.execSQL("UPDATE entries SET filed_at=saved_at WHERE list_edited=1");
        database.execSQL("""
                CREATE INDEX entries_by_manual_filing ON entries(list_id,filed_at DESC)
                WHERE list_edited=1
                """);
    }

    private void addSourceLookup() {
        database.execSQL("ALTER TABLE entries ADD COLUMN source_resolution TEXT NOT NULL DEFAULT ''");
        database.execSQL("ALTER TABLE entries ADD COLUMN source_attempts INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE entries ADD COLUMN source_zone TEXT NOT NULL DEFAULT ''");
        database.execSQL("""
                CREATE INDEX entries_by_source_lookup
                ON entries(source_package,source_resolution,source_attempts,saved_at,id)
                WHERE source_url IS NULL
                """);
    }

    private void initializeSourceLookups() {
        List<String> args = new ArrayList<>();
        args.add(NotebookStore.SOURCE_PENDING);
        args.add(ZoneId.systemDefault().getId());
        args.addAll(AUTOMATIC_SOURCE_PACKAGES);
        database.execSQL("""
                UPDATE entries SET source_url=NULL, source_resolution=?,
                    source_zone=CASE WHEN source_zone='' THEN ? ELSE source_zone END
                WHERE source_resolution='' AND source_attempts=0
                    AND (source_url IS NULL OR trim(source_url, char(9,10,11,12,13,32))='')
                    AND
                """ + idsClause("source_package", AUTOMATIC_SOURCE_PACKAGES), args.toArray());
    }

    void close() {
        database.close();
    }

    NotebookStore.Entry getEntry(String id) {
        try (Cursor row = database.rawQuery(ENTRY_COLUMNS + "WHERE e.id=?", new String[]{id})) {
            return row.moveToFirst() ? readEntry(row) : null;
        }
    }

    List<NotebookStore.Entry> getEntries(List<String> ids) {
        try (Cursor rows = database.rawQuery(ENTRY_COLUMNS + "WHERE " + idsClause("e.id", ids),
                ids.toArray(new String[0]))) {
            var byId = new HashMap<String, NotebookStore.Entry>();
            while (rows.moveToNext()) {
                NotebookStore.Entry entry = readEntry(rows);
                byId.put(entry.id, entry);
            }
            List<NotebookStore.Entry> ordered = new ArrayList<>(ids.size());
            for (String id : ids) {
                NotebookStore.Entry entry = byId.get(id);
                if (entry != null) ordered.add(entry);
            }
            return ordered;
        }
    }

    List<NotebookStore.Entry> entries(String listId, Long from, Long to, String order, int limit,
            int offset, Predicate<NotebookStore.Entry> matches) {
        StringBuilder sql = new StringBuilder(ENTRY_COLUMNS).append("WHERE 1");
        List<String> args = new ArrayList<>();
        if (listId != null) {
            sql.append(" AND e.list_id=? AND e.filing_pending=0");
            args.add(listId);
        }
        if (from != null) {
            sql.append(" AND e.saved_at>=?");
            args.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND e.saved_at<?");
            args.add(to.toString());
        }
        sql.append(" ORDER BY ").append(order).append(",e.id");
        if (matches == null) {
            sql.append(" LIMIT ? OFFSET ?");
            args.add(Integer.toString(limit));
            args.add(Integer.toString(offset));
        }
        try (Cursor rows = database.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            List<NotebookStore.Entry> entries = new ArrayList<>();
            int matchingRows = 0;
            while (entries.size() < limit && rows.moveToNext()) {
                NotebookStore.Entry entry = readEntry(rows);
                if (matches == null || (matches.test(entry) && matchingRows++ >= offset)) {
                    entries.add(entry);
                }
            }
            return entries;
        }
    }

    List<NotebookStore.Entry> pendingAnalysisEntries(int limit) {
        String accounts = idsClause("e.source_package", ACCOUNT_SOURCE_PACKAGES);
        try (Cursor rows = database.rawQuery(ENTRY_COLUMNS
                + "WHERE e.filing_pending=1 OR e.summary='' OR e.source_url IS NOT NULL OR "
                + accounts + " ORDER BY e.filing_pending DESC,(e.summary='') DESC,e.saved_at DESC,e.id",
                ACCOUNT_SOURCE_PACKAGES.toArray(new String[0]))) {
            List<NotebookStore.Entry> entries = new ArrayList<>();
            while (entries.size() < limit && rows.moveToNext()) {
                var entry = readEntry(rows);
                if (entry.filingPending || entry.summary.isEmpty() || entry.needsSourceEvidence()) entries.add(entry);
            }
            return entries;
        }
    }

    private static NotebookStore.Entry readEntry(Cursor row) {
        return new NotebookStore.Entry(row.getString(0), row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), row.getString(5), row.getLong(6),
                row.getString(7), row.getString(8), row.getLong(9), row.getString(10),
                row.getString(11), row.getString(12), row.getLong(13),
                row.getInt(14) != 0, row.getInt(15) != 0,
                row.getString(16), row.getInt(17), row.getString(18), row.getInt(19) != 0, row.getString(20));
    }

    void insertEntry(NotebookStore.Entry entry) {
        ContentValues values = new ContentValues();
        values.put("id", entry.id);
        values.put("title", entry.title);
        values.put("source_package", entry.sourcePackage);
        values.put("source_label", entry.sourceLabel);
        values.put("source_url", entry.sourceUrl);
        values.put("captured_at", entry.capturedAt);
        values.put("sha256", entry.sha256);
        values.put("mime_type", entry.mimeType);
        values.put("size_bytes", entry.sizeBytes);
        values.put("summary", entry.summary);
        values.put("list_id", entry.listId);
        values.put("description", entry.description);
        values.put("saved_at", entry.savedAt);
        values.put("source_resolution", entry.sourceResolution);
        values.put("source_attempts", entry.sourceAttempts);
        values.put("source_zone", entry.sourceZone);
        values.put("filing_pending", entry.filingPending);
        values.put("source_evidence", entry.sourceEvidence);
        database.insertOrThrow("entries", null, values);
    }

    NotebookStore.Entry nextSourceLookup() {
        List<String> args = new ArrayList<>();
        args.add(NotebookStore.SOURCE_PENDING);
        args.addAll(AUTOMATIC_SOURCE_PACKAGES);
        try (Cursor row = database.rawQuery(ENTRY_COLUMNS + "WHERE " + PENDING_SOURCE_LOOKUP
                + " AND (e.source_evidence<>'' OR e.summary<>'') AND " + idsClause("source_package", AUTOMATIC_SOURCE_PACKAGES)
                + " ORDER BY e.source_attempts,e.saved_at DESC,e.id LIMIT 1",
                args.toArray(new String[0]))) {
            return row.moveToFirst() ? readEntry(row) : null;
        }
    }

    boolean updateSourceLookup(NotebookStore.Entry lookup, ContentValues values) {
        return database.update("entries", values,
                PENDING_SOURCE_LOOKUP + " AND source_package=? AND id=? AND saved_at=? AND source_attempts=?",
                new String[]{NotebookStore.SOURCE_PENDING, lookup.sourcePackage, lookup.id,
                        Long.toString(lookup.savedAt), Integer.toString(lookup.sourceAttempts)}) == 1;
    }

    void updateEntries(List<String> ids, ContentValues values) {
        database.update("entries", values, idsClause("id", ids), ids.toArray(new String[0]));
    }

    void removeEntries(List<String> ids) {
        database.delete("entries", idsClause("id", ids), ids.toArray(new String[0]));
    }

    private static String idsClause(String column, List<String> ids) {
        return column + " IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
    }

    List<NotebookStore.SavedList> lists() {
        try (Cursor rows = database.rawQuery(LIST_COLUMNS + "ORDER BY l.rowid", null)) {
            List<NotebookStore.SavedList> lists = new ArrayList<>();
            while (rows.moveToNext()) lists.add(readList(rows));
            lists.sort(Comparator.comparingInt(list -> defaultListOrder(list.id)));
            return lists;
        }
    }

    private static int defaultListOrder(String id) {
        List<NotebookStore.DefaultList> defaults = NotebookStore.DEFAULT_LISTS;
        for (int index = 0; index < defaults.size(); index++) {
            if (defaults.get(index).id().equals(id)) return index;
        }
        return defaults.size();
    }

    List<NotebookStore.FilingExample> filingExamples(String listId, String excludedEntryId) {
        List<NotebookStore.FilingExample> examples = new ArrayList<>(FILING_EXAMPLES_PER_LIST);
        try (Cursor rows = database.rawQuery("""
                SELECT title,summary FROM entries
                WHERE list_id=? AND list_edited=1 AND id<>?
                ORDER BY filed_at DESC,rowid DESC
                """, new String[]{listId, excludedEntryId})) {
            while (examples.size() < FILING_EXAMPLES_PER_LIST && rows.moveToNext()) {
                var example = new NotebookStore.FilingExample(rows.getString(0), rows.getString(1));
                if (example.summary().isEmpty()
                        && (example.title().isEmpty() || "Screenshot".equalsIgnoreCase(example.title()))) {
                    continue;
                }
                examples.add(example);
            }
        }
        return List.copyOf(examples);
    }

    NotebookStore.SavedList getList(String id) {
        return findList("id", id);
    }

    NotebookStore.SavedList listNamed(String name) {
        return findList("name_key", name.toLowerCase(Locale.ROOT));
    }

    private NotebookStore.SavedList findList(String column, String value) {
        try (Cursor row = database.rawQuery(LIST_COLUMNS + "WHERE l." + column + "=?",
                new String[]{value})) {
            return row.moveToFirst() ? readList(row) : null;
        }
    }

    private static NotebookStore.SavedList readList(Cursor row) {
        return new NotebookStore.SavedList(row.getString(0), row.getString(1), row.getString(2),
                row.getInt(3));
    }

    void insertList(String id, String name, String rule) {
        ContentValues values = listValues(name, rule);
        values.put("id", id);
        database.insertOrThrow("lists", null, values);
    }

    void updateList(String id, String name, String rule) {
        database.update("lists", listValues(name, rule), "id=?", new String[]{id});
    }

    private static ContentValues listValues(String name, String rule) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("name_key", name.toLowerCase(Locale.ROOT));
        values.put("rule", rule);
        return values;
    }
}
