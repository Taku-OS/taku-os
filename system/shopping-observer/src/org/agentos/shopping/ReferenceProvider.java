// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;

/** A single-token read grant, not a filesystem or directory API. */
public final class ReferenceProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private ReferenceStore.Entry entry(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !ReferenceStore.AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
            throw new FileNotFoundException("Unknown reference");
        String token = uri.getPathSegments().get(0);
        if (!ReferenceStore.validToken(token)) throw new FileNotFoundException("Unknown reference");
        if (!ReferenceStore.uri(token).equals(uri)) throw new FileNotFoundException("Unknown reference");
        if (Binder.getCallingUid() != Process.myUid()
                && getContext().checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("A read grant is required");
        ReferenceStore.Entry entry = ReferenceStore.get(getContext(), token);
        if (entry == null) throw new FileNotFoundException("Reference expired");
        return entry;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read-only reference");
        return ParcelFileDescriptor.open(entry(uri).file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { entry(uri); return "image/jpeg"; }
        catch (FileNotFoundException unavailable) { throw new IllegalArgumentException("Reference unavailable"); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (selection != null || args != null || sort != null) throw new IllegalArgumentException("No reference search");
        try {
            ReferenceStore.Entry entry = entry(uri);
            String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "try-on-reference.jpg";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = entry.file().length();
                else throw new IllegalArgumentException("Unknown reference column");
            }
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException unavailable) { throw new IllegalArgumentException("Reference unavailable"); }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
