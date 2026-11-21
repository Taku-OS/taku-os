// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import org.json.JSONObject;

/** Shared App Permission Center enforcement for the two try-on applications. */
public final class AgentAccess {
    private static final Uri PERMISSIONS = Uri.parse("content://org.agentos.assistant.permissions");
    private AgentAccess() {}

    /** callerUid must be captured from Binder before clearing identity, never supplied by API input. */
    public static void enforce(Context context, int callerUid) {
        if (!allowed(context, callerUid)) throw new SecurityException("permission_denied");
    }

    public static boolean allowed(Context context, int callerUid) {
        if (context == null || callerUid < 0) return false;
        if (callerUid == context.getApplicationInfo().uid || callerUid == 0
                || callerUid == 1000 || callerUid == 2000 || platformSigned(context, callerUid)) return true;
        // Built-in enforcement hosts use the platform certificate, while keeping their own UID.
        // A development-signed host cannot query on behalf of third-party apps and fails closed.
        long identity = Binder.clearCallingIdentity();
        try {
            String input = new JSONObject().put("uid", callerUid).put("scope", "taku.control").toString();
            Bundle reply = context.getContentResolver().call(PERMISSIONS, "check", input, null);
            if (reply == null) return false;
            String encoded = reply.getString("result");
            if (encoded == null || encoded.length() > 16384) return false;
            JSONObject verdict = new JSONObject(encoded);
            return Boolean.TRUE.equals(verdict.opt("ok")) && Boolean.TRUE.equals(verdict.opt("allowed"));
        } catch (Exception unavailable) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static boolean platformSigned(Context context, int uid) {
        try {
            PackageManager manager = context.getPackageManager();
            String[] packages = manager.getPackagesForUid(uid);
            if (packages != null) for (String name : packages)
                if (manager.checkSignatures("android", name) == PackageManager.SIGNATURE_MATCH) return true;
        } catch (RuntimeException unavailable) { /* No guessed trust on an unavailable package manager. */ }
        return false;
    }
}
