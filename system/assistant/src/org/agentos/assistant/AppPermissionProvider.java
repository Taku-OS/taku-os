package org.agentos.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.io.IOException;
import org.json.JSONObject;

/**
 * Central cross-process enforcement & CLI management endpoint for Taku App Permissions:
 * `content://org.agentos.assistant.permissions`.
 *
 * - `check`: callable by trusted Taku enforcement hosts (Memory, Home, Audio, GodMode, SystemUI)
 *   to authorize a caller UID against `memory.read`, `memory.write`, `taku.control`, or `taku.root`.
 * - `read` / `setMode` / `setScope` / `reset`: callable by root/system/shell CLI (`origin="cli"`),
 *   subject to manual-mode and root-escalation locks.
 */
public final class AppPermissionProvider extends ContentProvider {
    public static final String AUTHORITY = "org.agentos.assistant.permissions";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** In-process check helper used by Assistant providers (`AgentOpenAccessProvider`, `SystemAiProvider`). */
    static boolean isAllowedLocal(Context context, int targetUid, String scope) {
        try {
            if (AppPermissionCatalog.isTrustedSubject(context, targetUid, scope)) {
                return true;
            }
            JSONObject app = AppPermissionCatalog.inspectSingleUid(context, targetUid);
            if (app == null) return false;
            JSONObject verdict = NativeClient.exchange(new JSONObject()
                    .put("op", "appPermissions")
                    .put("action", "check")
                    .put("scope", scope)
                    .put("app", app));
            return verdict.optBoolean("allowed", false);
        } catch (Exception failClosed) {
            return false;
        }
    }

    static void enforceLocal(Context context, int targetUid, String scope) throws SecurityException {
        if (!isAllowedLocal(context, targetUid, scope)) {
            throw new SecurityException("PERMISSION_DENIED");
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        int callerUid = Binder.getCallingUid();
        Context context = getContext();
        if (context == null) {
            out.putString("result", errorJson("UNAVAILABLE", "Permission provider not ready"));
            return out;
        }
        if ("check".equals(method)) {
            if (!AppPermissionCatalog.isTrustedEnforcementHost(context, callerUid)) {
                out.putString("result", errorJson("PERMISSION_DENIED", "Untrusted permission check host"));
                return out;
            }
            long token = Binder.clearCallingIdentity();
            try {
                JSONObject input = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
                int targetUid = input.getInt("uid");
                String scope = input.getString("scope");
                if (AppPermissionCatalog.isTrustedSubject(context, targetUid, scope)) {
                    out.putString("result", new JSONObject()
                            .put("ok", true)
                            .put("allowed", true)
                            .put("by", "trusted")
                            .toString());
                    return out;
                }
                JSONObject app = AppPermissionCatalog.inspectSingleUid(context, targetUid);
                if (app == null) {
                    out.putString("result", new JSONObject()
                            .put("ok", true)
                            .put("allowed", false)
                            .put("by", "ambiguous_or_missing_uid")
                            .toString());
                    return out;
                }
                JSONObject verdict = NativeClient.exchange(new JSONObject()
                        .put("op", "appPermissions")
                        .put("action", "check")
                        .put("scope", scope)
                        .put("app", app));
                out.putString("result", new JSONObject()
                        .put("ok", true)
                        .put("allowed", verdict.optBoolean("allowed", false))
                        .put("by", verdict.optString("by", ""))
                        .toString());
                return out;
            } catch (Exception failure) {
                out.putString("result", errorJson("PERMISSION_CHECK_FAILED", failure.getMessage()));
                return out;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // CLI management endpoints: strictly restricted to root / system / adb shell.
        if (!AppPermissionCatalog.isSystemOrShellUid(callerUid)) {
            out.putString("result", errorJson("PERMISSION_DENIED", "App permission CLI requires shell or system"));
            return out;
        }
        long token = Binder.clearCallingIdentity();
        try {
            JSONObject input = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            String brokerAction;
            switch (method == null ? "" : method) {
                case "read":
                    brokerAction = "snapshot";
                    break;
                case "setMode":
                case "setScope":
                case "reset":
                    brokerAction = method;
                    break;
                default:
                    throw new IOException("UNKNOWN_METHOD");
            }
            JSONObject request = new JSONObject(input.toString())
                    .put("op", "appPermissions")
                    .put("action", brokerAction)
                    .put("origin", "cli")
                    .put("installedApps", AppPermissionCatalog.collectExternalApps(context));
            JSONObject snapshot = NativeClient.exchange(request);
            out.putString("result", new JSONObject().put("ok", true).put("result", snapshot).toString());
            return out;
        } catch (Exception failure) {
            String msg = failure.getMessage() == null ? "OPERATION_FAILED" : failure.getMessage();
            String code = msg.matches("[A-Z][A-Z0-9_]{2,60}") ? msg : "INVALID_ARGUMENT";
            out.putString("result", errorJson(code, msg));
            return out;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static String errorJson(String code, String message) {
        try {
            return new JSONObject()
                    .put("ok", false)
                    .put("code", code)
                    .put("message", message == null ? code : message)
                    .toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Use content call");
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
