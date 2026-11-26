package org.agentos.notebook;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class SystemScreenshots extends ContentObserver implements AutoCloseable {
    record Capture(Uri uri, String mime, long capturedAt, long mediaReadyAt, long mediaReadyWallTime,
            long notificationAt) {}
    private static final Uri IMAGES = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final String[] COLUMNS = {"_id", "mime_type", "datetaken"};
    private final ContentResolver resolver;
    private final Handler worker;
    private final Consumer<Capture> receiver;
    private final Runnable scan = this::scan;
    private final Map<Long, Long> seen = new HashMap<>();
    private final long startedAt = System.currentTimeMillis();

    SystemScreenshots(ContentResolver resolver, Handler worker, Consumer<Capture> receiver) {
        super(worker);
        this.resolver = resolver;
        this.worker = worker;
        this.receiver = receiver;
        resolver.registerContentObserver(IMAGES, true, this);
        worker.post(scan);
    }

    @Override public void onChange(boolean self, Uri changed) {
        long notifiedAt = SystemClock.elapsedRealtime();
        worker.removeCallbacks(scan);
        worker.postDelayed(scan, 140);
        if (changed == null) return;
        try {
            long id = ContentUris.parseId(changed);
            if (id > 0 && changed.equals(ContentUris.withAppendedId(IMAGES, id)) && !seen.containsKey(id)) {
                scan(changed, notifiedAt);
            }
        } catch (NumberFormatException ignored) { }
    }

    private void scan() {
        scan(IMAGES, 0);
    }

    private void scan(Uri uri, long notifiedAt) {
        long now = System.currentTimeMillis();
        long cutoff = Math.max(startedAt, now - 8000);
        seen.values().removeIf(capturedAt -> capturedAt < cutoff);
        try (var rows = resolver.query(uri, COLUMNS,
                "date_added >= ? AND is_pending = 0 AND is_trashed = 0 AND owner_package_name = ?"
                        + " AND (relative_path = ? OR relative_path = ?)",
                new String[]{Long.toString(cutoff / 1000), "com.android.systemui", "Pictures/Screenshots/", "DCIM/Screenshots/"},
                "_id ASC")) {
            while (rows != null && rows.moveToNext()) {
                long id = rows.getLong(0);
                long capturedAt = rows.getLong(2);
                if (capturedAt <= 0) continue;
                if (capturedAt >= cutoff && capturedAt <= now + 1000 && seen.putIfAbsent(id, capturedAt) == null) {
                    receiver.accept(new Capture(ContentUris.withAppendedId(IMAGES, id), rows.getString(1),
                            capturedAt, SystemClock.elapsedRealtime(), System.currentTimeMillis(), notifiedAt));
                }
            }
        } catch (RuntimeException failure) {
            Log.w("NotebookScreenshot", "Unable to read system screenshot metadata", failure);
        }
    }

    @Override public void close() {
        resolver.unregisterContentObserver(this);
        worker.removeCallbacks(scan);
    }
}
