package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AppPermissionPolicyTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static JSONObject app(String pkg, String label, String cert, String fp, boolean internet) throws Exception {
        return new JSONObject()
                .put("uid", 10200)
                .put("packageName", pkg)
                .put("label", label)
                .put("versionName", "1.0.0")
                .put("versionCode", 10L)
                .put("enabled", true)
                .put("certSha256", cert)
                .put("apkFingerprint", fp)
                .put("hasInternet", internet)
                .put("hasHighRiskPermissions", false)
                .put("installerPackage", "com.android.shell");
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("app-perm-policy-test").toFile();
        File policyFile = new File(dir, "app-permissions.json");
        JSONArray catalog = new JSONArray()
                .put(app("com.user.habit", "Habit", "aa11", "aa11:10:1", false))
                .put(app("com.example.shop", "Shop", "bb22", "bb22:10:1", true));

        AppPermissionPolicy.Store store = new AppPermissionPolicy.Store(
                policyFile,
                (a, scope) -> new AppPermissionPolicy.AutoVerdict(
                        !a.optBoolean("hasInternet"), true, "Test auto evaluator"));

        // 1. Default is strict deny.
        JSONObject initial = store.snapshot(catalog, "");
        check("deny".equals(initial.getString("mode")), "default mode must be deny");
        check(!initial.getBoolean("allowRootInYolo"), "default allowRootInYolo must be false");
        check(!store.check(catalog.getJSONObject(0), "memory.read").getBoolean("allowed"),
                "deny mode blocks third-party memory.read");

        // 2. Manual mode blocks CLI escalation & per-app CLI mutation, allows UI mutation.
        store.setMode("manual", null, "ui", catalog);
        try {
            store.setScope("com.user.habit", "memory.read", true, "cli", catalog);
            throw new AssertionError("CLI must not grant scopes in manual mode");
        } catch (IOException expected) {
            check("MANUAL_MODE_LOCKED".equals(expected.getMessage()), "expected MANUAL_MODE_LOCKED");
        }
        try {
            store.setMode("yolo", false, "cli", catalog);
            throw new AssertionError("CLI must not escalate out of manual mode to yolo");
        } catch (IOException expected) {
            check("MANUAL_MODE_LOCKED".equals(expected.getMessage()), "expected MANUAL_MODE_LOCKED on mode");
        }

        store.setScope("com.user.habit", "memory.read", true, "ui", catalog);
        check(store.check(catalog.getJSONObject(0), "memory.read").getBoolean("allowed"),
                "manual UI grant allows memory.read");
        check(!store.check(catalog.getJSONObject(0), "taku.root").getBoolean("allowed"),
                "ungranted root stays denied");

        // 3. Certificate rotation invalidates manual grant (anti-package-hijack).
        JSONObject hijacked = app("com.user.habit", "Habit", "evil99", "evil99:11:2", true);
        check(!store.check(hijacked, "memory.read").getBoolean("allowed"),
                "different signing certificate must invalidate stored grant");

        // 4. Auto mode grants non-root via evaluator, never grants memory.write or taku.root,
        //    and re-evaluates if apkFingerprint changes.
        store.reset("com.user.habit", "ui", catalog);
        store.setMode("auto", null, "ui", catalog);
        check(store.check(catalog.getJSONObject(0), "memory.read").getBoolean("allowed"),
                "auto mode allows safe offline app memory.read");
        check(!store.check(catalog.getJSONObject(0), "memory.write").getBoolean("allowed"),
                "auto mode never auto-grants memory.write");
        check(!store.check(catalog.getJSONObject(0), "taku.root").getBoolean("allowed"),
                "auto mode never auto-grants taku.root");

        // 5. YOLO mode opens non-root scopes, keeps root off until allowRootInYolo=true,
        //    and materializes effective scopes cleanly when toggling one scope off.
        store.setMode("yolo", false, "ui", catalog);
        check(store.check(catalog.getJSONObject(1), "memory.write").getBoolean("allowed"),
                "yolo mode opens memory.write");
        check(!store.check(catalog.getJSONObject(1), "taku.root").getBoolean("allowed"),
                "yolo mode keeps root closed when allowRootInYolo is false");
        JSONObject afterToggle = store.setScope("com.example.shop", "memory.write", false, "ui", catalog);
        JSONArray apps = afterToggle.getJSONArray("apps");
        JSONObject shop = null;
        for (int i = 0; i < apps.length(); i++) {
            if ("com.example.shop".equals(apps.getJSONObject(i).getString("packageName"))) {
                shop = apps.getJSONObject(i);
            }
        }
        check(shop != null, "shop present in snapshot");
        check(shop.getJSONObject("effectiveScopes").getBoolean("memory.read"),
                "toggling memory.write off in yolo must preserve memory.read=true");
        check(!shop.getJSONObject("effectiveScopes").getBoolean("memory.write"),
                "toggling memory.write off in yolo must enforce memory.write=false");
        check(!shop.getJSONObject("effectiveScopes").getBoolean("taku.root"),
                "toggling in yolo must not materialize taku.root=true");

        store.setMode("yolo", true, "ui", catalog);
        check(store.check(catalog.getJSONObject(1), "taku.root").getBoolean("allowed"),
                "allowRootInYolo=true opens root in yolo mode");
        store.setMode("deny", null, "ui", catalog);
        check(!store.snapshot(catalog, "").getBoolean("allowRootInYolo"),
                "leaving yolo resets allowRootInYolo to false");

        // 6. Fail-closed on corrupt file.
        Files.writeString(policyFile.toPath(), "{corrupt json");
        check(!store.check(catalog.getJSONObject(0), "taku.control").getBoolean("allowed"),
                "corrupt policy file must fail closed");

        System.out.println("AppPermissionPolicyTest passed");
    }
}
