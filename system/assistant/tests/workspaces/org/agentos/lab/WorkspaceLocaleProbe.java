package org.agentos.lab;

import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.Method;

/** Root-only disposable-emulator helper for checking that UI follows the system locale. */
public final class WorkspaceLocaleProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Locale language tag required");
        Class<?> manager = Class.forName("android.app.ActivityManager");
        Method getService = manager.getDeclaredMethod("getService");
        getService.setAccessible(true);
        Object service = getService.invoke(null);
        Class<?> contract = Class.forName("android.app.IActivityManager");
        Configuration current = (Configuration) contract.getMethod("getConfiguration").invoke(service);
        Configuration next = new Configuration(current);
        next.setLocales(LocaleList.forLanguageTags(args[0]));
        Configuration.class.getField("userSetLocale").setBoolean(next, true);
        contract.getMethod("updatePersistentConfiguration", Configuration.class).invoke(service, next);
        Configuration result = (Configuration) contract.getMethod("getConfiguration").invoke(service);
        if (!result.getLocales().toLanguageTags().equals(args[0])) throw new IllegalStateException("System locale did not change");
        System.out.println("System locales: " + result.getLocales().toLanguageTags());
    }
}
