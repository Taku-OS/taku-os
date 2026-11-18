package com.android.server.pm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import com.android.role.controller.behavior.HomeRoleBehavior;
import com.android.role.controller.behavior.AssistantRoleBehavior;
import com.android.role.controller.model.Role;
import java.util.List;

public class HomePolicyTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static Intent home() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME); }
    static ResolveInfo candidate(String pkg, String activity, int priority) {
        ResolveInfo r = new ResolveInfo();
        r.activityInfo.packageName = pkg; r.activityInfo.name = activity;
        r.activityInfo.applicationInfo.packageName = pkg; r.priority = priority;
        return r;
    }
    public static void main(String[] args) {
        String pkg = TakuHomePolicy.PACKAGE;
        ResolveInfo taku = candidate(pkg, TakuHomePolicy.ACTIVITY, 0);
        ResolveInfo quickstep = candidate("com.android.launcher3", "QuickstepLauncher", 0);
        ResolveInfo old = candidate("org.agentos.assistant", "CodexHomeActivity", 0);
        List<ResolveInfo> homes = List.of(quickstep, old, taku);
        // First boot/no preferred activity, and stale preferences: list order cannot win.
        check(TakuHomePolicy.resolve(pkg, home(), null, homes) == taku);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of(taku, old, quickstep)) == taku);
        check(TakuHomePolicy.resolve(pkg, home().addCategory(Intent.CATEGORY_DEFAULT), null, homes) == taku);
        check(TakuHomePolicy.resolve("", home(), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home(), null, null) == null);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of()) == null);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of(quickstep, old)) == null);
        // Preserve Quickstep's explicit and package-scoped queries, setup and other intents.
        check(TakuHomePolicy.resolve(pkg, home().setPackage("com.android.launcher3"), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home().setComponent(new Object()), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home().setSelector(home()), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home().setData(new Object()), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home(), "text/plain", homes) == null);
        check(TakuHomePolicy.resolve(pkg, new Intent("android.intent.action.VIEW"), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.SECONDARY_HOME"), null, homes) == null);
        check(TakuHomePolicy.resolve(pkg, home().addCategory("unexpected"), null, homes) == null);
        ResolveInfo setup = candidate("setup.wizard", "Setup", 5);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of(setup, taku)) == null);
        ResolveInfo locked = candidate("com.android.settings", "FallbackHome", -1000);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of(locked)) == null);
        check(TakuHomePolicy.resolve(pkg, home(), null, List.of(locked, taku)) == taku);
        taku.activityInfo.enabled = false;
        check(TakuHomePolicy.resolve(pkg, home(), null, homes) == null);
        taku.activityInfo.enabled = true; taku.activityInfo.applicationInfo.enabled = false;
        check(TakuHomePolicy.resolve(pkg, home(), null, homes) == null);
        taku.activityInfo.applicationInfo.enabled = true; taku.activityInfo.exported = false;
        check(TakuHomePolicy.resolve(pkg, home(), null, homes) == null);
        taku.activityInfo.exported = true;

        // Execute the patched upstream HomeRoleBehavior, not a second policy implementation.
        HomeRoleBehavior behavior = new HomeRoleBehavior();
        Context ctx = new Context(); ctx.pm.homes.addAll(homes);
        UserHandle owner = new UserHandle(0); Role role = new Role();
        SystemProperties.home = pkg;
        check(behavior.getDefaultHoldersAsUser(role, owner, ctx).equals(List.of(pkg)));
        check(pkg.equals(behavior.getFallbackHolderAsUser(role, owner, ctx)));
        check(behavior.getQualifyingPackagesAsUser(role, owner, ctx).equals(List.of(pkg)));
        check(Boolean.FALSE.equals(behavior.isPackageQualifiedAsUser(role, "com.android.launcher3", owner, ctx)));
        check(Boolean.FALSE.equals(behavior.isPackageQualifiedAsUser(role, "org.agentos.assistant", owner, ctx)));
        check(behavior.isPackageQualifiedAsUser(role, pkg, owner, ctx) == null);
        UserHandle other = new UserHandle(10); ctx.users.put(10, new Context());
        check(behavior.getDefaultHoldersAsUser(role, other, ctx) == null);
        check(behavior.isPackageQualifiedAsUser(role, "com.android.launcher3", other, ctx) == null);
        other.profile = true; ctx.users.get(10).pm.homes.add(taku);
        check(behavior.getDefaultHoldersAsUser(role, other, ctx) == null);
        check(!behavior.isAvailableAsUser(role, other, ctx));
        taku.activityInfo.enabled = false;
        check(behavior.getDefaultHoldersAsUser(role, owner, ctx) == null);
        taku.activityInfo.enabled = true;
        SystemProperties.home = "";
        check(behavior.getDefaultHoldersAsUser(role, owner, ctx) == null);
        check(behavior.getQualifyingPackagesAsUser(role, owner, ctx) == null);
        check(behavior.isPackageQualifiedAsUser(role, "com.android.launcher3", owner, ctx) == null);
        check(behavior.getFallbackHolderAsUser(role, owner, ctx) == null); // original tied priorities
        AssistantRoleBehavior assistant = new AssistantRoleBehavior();
        ResolveInfo voice = new ResolveInfo();
        voice.serviceInfo = new android.content.pm.ServiceInfo();
        voice.serviceInfo.packageName = pkg;
        voice.serviceInfo.name = "org.agentos.agent.home.assistant.SystemAssistantService";
        ctx.pm.services.add(voice);
        SystemProperties.home = pkg;
        check(assistant.getDefaultHoldersAsUser(role, owner, ctx).equals(List.of(pkg)));
        check(pkg.equals(assistant.getFallbackHolderAsUser(role, owner, ctx)));
        check(assistant.getQualifyingPackagesAsUser(role, owner, ctx).equals(List.of(pkg)));
        check(!assistant.isPackageQualifiedAsUser(role, "org.agentos.assistant", owner, ctx));
        check(assistant.isPackageQualifiedAsUser(role, pkg, owner, ctx));
        check(assistant.getDefaultHoldersAsUser(role, other, ctx) == null);
        voice.serviceInfo.validMetadata = false;
        check(assistant.getDefaultHoldersAsUser(role, owner, ctx) == null);
        voice.serviceInfo.validMetadata = true; voice.serviceInfo.enabled = false;
        check(assistant.getFallbackHolderAsUser(role, owner, ctx) == null);
        voice.serviceInfo.enabled = true; voice.serviceInfo.permission = "incorrect";
        check(assistant.getDefaultHoldersAsUser(role, owner, ctx) == null);
        voice.serviceInfo.permission = "android.permission.BIND_VOICE_INTERACTION";
        SystemProperties.home = "";
        check(assistant.getDefaultHoldersAsUser(role, owner, ctx) == null);
        System.out.println("HOME policy: " + checks + " assertions passed");
    }
}
