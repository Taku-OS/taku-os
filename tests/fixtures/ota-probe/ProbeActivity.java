package org.taku.otaprobe;

import android.app.Activity;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** An isolated, account-free fixture. No permissions, network, backup or user-app reads. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String nonce = getIntent().getStringExtra("nonce");
        if (nonce == null || !nonce.matches("[0-9a-f]{64}")) {
            finish();
            return;
        }
        try {
            // A missing final file must not look like successful initialization.
            File finalFile = new File(getFilesDir(), "ota_probe.bin");
            if (finalFile.exists() && !finalFile.delete()) throw new IllegalStateException("Cannot reset probe marker");
            boolean committed = getSharedPreferences("ota_probe", MODE_PRIVATE).edit().clear()
                    .putString("nonce", nonce).putInt("schema", 1).commit();
            if (!committed) throw new IllegalStateException("Preferences were not committed");
            File database = getDatabasePath("ota_probe.db");
            if (!database.getParentFile().isDirectory() && !database.getParentFile().mkdirs()) {
                throw new IllegalStateException("Cannot create probe database directory");
            }
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                db.disableWriteAheadLogging();
                db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, nonce TEXT NOT NULL)");
                db.beginTransaction();
                try {
                    db.delete("probe", null, null);
                    ContentValues row = new ContentValues();
                    row.put("id", 1);
                    row.put("nonce", nonce);
                    db.insertOrThrow("probe", null, row);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            // This marker is written last, after preferences and SQLite close.
            try (FileOutputStream output = new FileOutputStream(finalFile)) {
                output.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Exception failure) {
            // No account values or generated probe nonce are logged.
            android.util.Log.e("TakuOtaProbe", "Probe initialization failed");
        } finally {
            finish();
        }
    }
}
