package org.agentos.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Resolves Android caller UIDs and enumerates external/third-party applications for the
 * App Permission Center without spawning subprocesses.
 */
final class AppPermissionCatalog {
    static final String SCOPE_MEMORY_READ = "memory.read";
    static final String SCOPE_MEMORY_WRITE = "memory.write";
    static final String SCOPE_TAKU_CONTROL = "taku.control";
    static final String SCOPE_TAKU_ROOT = "taku.root";

    private static final Set<String> HIGH_RISK_PERMISSIONS = new HashSet<>(Arrays.asList(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE));

    private AppPermissionCatalog() {}

    static boolean isSystemOrShellUid(int uid) {
        return uid == 0 || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID;
    }

    static boolean isPlatformSigned(PackageManager pm, String packageName) {
        try {
            return pm.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Trusted enforcement hosts allowed to call `content://org.agentos.assistant.permissions` `check`:
     * root/system/shell, platform-signed first-party apps (Memory, Agent/Home, SystemUI, GodMode UI),
     * and the preinstalled system Taku Audio app.
     */
    static boolean isTrustedEnforcementHost(Context context, int callerUid) {
        if (isSystemOrShellUid(callerUid) || callerUid == context.getApplicationInfo().uid) {
            return true;
        }
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(callerUid);
        if (pkgs == null || pkgs.length == 0) return false;
        for (String pkg : pkgs) {
            if (isPlatformSigned(pm, pkg)) return true;
            if ("org.agentos.audio".equals(pkg)) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /**
     * Fast-path check for first-party / system / shell callers (0 socket I/O).
     * Ordinary platform-signed UI apps receive `memory.read`, `memory.write`, and `taku.control`,
     * while `taku.root` among ordinary-UID platform apps is granted only to `org.taku.plugins.ui`.
     */
    static boolean isTrustedSubject(Context context, int targetUid, String scope) {
        if (isSystemOrShellUid(targetUid)) return true;
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(targetUid);
        if (pkgs == null || pkgs.length != 1) return false;
        String pkg = pkgs[0];
        if (!isPlatformSigned(pm, pkg)) return false;
        if (SCOPE_TAKU_ROOT.equals(scope)) {
            return "org.taku.plugins.ui".equals(pkg);
        }
        return true;
    }

    static JSONObject inspectSingleUid(Context context, int uid) throws Exception {
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length != 1) return null;
        PackageInfo info = pm.getPackageInfo(
                pkgs[0],
                PackageManager.GET_SIGNING_CERTIFICATES
                        | PackageManager.GET_PERMISSIONS
                        | PackageManager.MATCH_DISABLED_COMPONENTS);
        return toAppDescriptor(pm, info);
    }

    static JSONArray collectExternalApps(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES
                | PackageManager.GET_PERMISSIONS
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        JSONArray out = new JSONArray();
        for (PackageInfo info : pm.getInstalledPackages(flags)) {
            ApplicationInfo ai = info.applicationInfo;
            if (ai == null || ai.uid < Process.FIRST_APPLICATION_UID) continue;
            String pkg = info.packageName;
            if (isPlatformSigned(pm, pkg)) continue;
            boolean systemImage = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (systemImage) continue;
            JSONObject descriptor = toAppDescriptor(pm, info);
            if (descriptor != null) out.put(descriptor);
        }
        return out;
    }

    private static JSONObject toAppDescriptor(PackageManager pm, PackageInfo info) throws Exception {
        ApplicationInfo ai = info.applicationInfo;
        if (ai == null) return null;
        String pkg = info.packageName;
        String cert = primaryCertSha256(info);
        if (cert.isEmpty()) return null;
        int enabledSetting = pm.getApplicationEnabledSetting(pkg);
        boolean enabled = enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                ? ai.enabled
                : enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        String label = String.valueOf(pm.getApplicationLabel(ai)).trim();
        if (label.isEmpty()) label = pkg;
        if (label.length() > 160) label = label.substring(0, 160);
        String versionName = info.versionName;
        if (versionName != null && versionName.length() > 120) {
            versionName = versionName.substring(0, 120);
        }
        long versionCode = info.getLongVersionCode();
        String fingerprint = cert + ":" + versionCode + ":" + info.lastUpdateTime;

        boolean hasInternet = false;
        boolean hasHighRisk = false;
        int permCount = 0;
        if (info.requestedPermissions != null) {
            permCount = info.requestedPermissions.length;
            for (String perm : info.requestedPermissions) {
                if (Manifest.permission.INTERNET.equals(perm)) hasInternet = true;
                if (HIGH_RISK_PERMISSIONS.contains(perm)) hasHighRisk = true;
            }
        }
        String installer = "";
        try {
            String src = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            if (src != null) installer = src;
        } catch (Exception ignored) {
        }
        return new JSONObject()
                .put("uid", ai.uid)
                .put("packageName", pkg)
                .put("label", label)
                .put("versionName", versionName == null || versionName.isEmpty() ? JSONObject.NULL : versionName)
                .put("versionCode", versionCode)
                .put("enabled", enabled)
                .put("certSha256", cert)
                .put("apkFingerprint", fingerprint)
                .put("hasInternet", hasInternet)
                .put("hasHighRiskPermissions", hasHighRisk)
                .put("permissionsCount", permCount)
                .put("installerPackage", installer);
    }

    private static String primaryCertSha256(PackageInfo info) throws Exception {
        if (info.signingInfo == null) return "";
        Signature[] signers = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
        if (signers == null || signers.length != 1) return "";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }
}
