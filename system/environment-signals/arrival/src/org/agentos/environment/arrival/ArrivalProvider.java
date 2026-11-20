package org.agentos.environment.arrival;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

public final class ArrivalProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String args, Bundle extras) {
        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        Bundle reply = new Bundle();
        JSONObject result;
        try {
            if (args != null && args.length() > 8192) throw new IllegalArgumentException("Arguments too large");
            result = ArrivalRuntime.get(getContext()).call(uid, method,
                    args == null || args.isEmpty() ? new JSONObject() : new JSONObject(args));
        } catch (ArrivalRuntime.Failure failure) {
            result = error(failure.code, failure.getMessage());
        } catch (IllegalArgumentException | JSONException invalid) {
            result = error("INVALID_ARGUMENT", invalid.getMessage());
        } catch (RuntimeException unavailable) {
            Log.w("TakuArrival", "Arrival request failed", unavailable);
            result = error("UNAVAILABLE", "Arrival monitoring is unavailable");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        reply.putString("result", result.toString());
        return reply;
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false).put("code", code).put("message", message);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override public String getType(Uri uri) { return "application/json"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("Use content call");
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
