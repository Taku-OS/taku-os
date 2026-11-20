package org.agentos.environment.arrival;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.agentos.environment.arrival.ArrivalStore.Owner;

final class ArrivalAccess {
    static final String AVAILABLE = "org.agentos.environment.arrival.AVAILABLE";
    private static final String SEND_PERMISSION = "org.agentos.permission.SEND_LOCATION_ARRIVAL";
    private static final Uri PERMISSIONS = Uri.parse("content://org.agentos.assistant.permissions");

    enum Verdict { ALLOWED, DENIED, UNAVAILABLE }

    private final Context context;
    private final PackageManager packages;

    ArrivalAccess(Context context) {
        this.context = context;
        packages = context.getPackageManager();
    }

    Owner identify(int uid) {
        if (uid == 0 || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) {
            return new Owner(uid, "", "");
        }
        String[] names = packages.getPackagesForUid(uid);
        if (names == null || names.length != 1) return null;
        try {
            PackageInfo info = packages.getPackageInfo(names[0], PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.applicationInfo == null || !info.applicationInfo.enabled || info.signingInfo == null) {
                return null;
            }
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers.length != 1) return null;
            byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            return new Owner(uid, names[0], HexFormat.of().formatHex(fingerprint) + ":" + info.firstInstallTime);
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Verdict check(Owner owner) {
        if (owner == null) return Verdict.DENIED;
        if (owner.packageName().isEmpty() || packages.checkSignatures("android", owner.packageName())
                == PackageManager.SIGNATURE_MATCH) return Verdict.ALLOWED;
        if (!hasBackgroundLocation(owner.packageName())) return Verdict.DENIED;
        try {
            JSONObject request = new JSONObject().put("uid", owner.uid()).put("scope", "taku.control");
            Bundle response = context.getContentResolver().call(PERMISSIONS, "check", request.toString(), null);
            if (response == null) return Verdict.UNAVAILABLE;
            JSONObject result = new JSONObject(response.getString("result", "{}"));
            if (!result.optBoolean("ok")) return Verdict.UNAVAILABLE;
            return result.getBoolean("allowed") ? Verdict.ALLOWED : Verdict.DENIED;
        } catch (Exception unavailable) {
            return Verdict.UNAVAILABLE;
        }
    }

    boolean hasBackgroundLocation(String packageName) {
        return packages.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, packageName)
                == PackageManager.PERMISSION_GRANTED
                && packages.checkPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean canReceive(Owner owner, String className) {
        Intent action = new Intent(AVAILABLE).setPackage(owner.packageName());
        for (ResolveInfo receiver : packages.queryBroadcastReceivers(action, 0)) {
            var info = receiver.activityInfo;
            if (info != null && info.exported && className.equals(info.name)
                    && SEND_PERMISSION.equals(info.permission)) return true;
        }
        return false;
    }
}
