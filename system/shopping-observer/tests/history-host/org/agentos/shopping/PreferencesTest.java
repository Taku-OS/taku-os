// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;

/** GUI set() and Agent configure() execute the same production validation/storage adapter. */
public final class PreferencesTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static final class Store implements SharedPreferences {
        final Map<String,Object> values = new HashMap<>(); boolean fail;
        public long getLong(String key,long fallback) { return fallback; }
        public String getString(String key,String fallback) { return fallback; }
        public boolean getBoolean(String key,boolean fallback) { return values.get(key) instanceof Boolean b ? b : fallback; }
        public Editor edit() { return new Editor() {
            final Map<String,Object> next = new HashMap<>();
            public Editor putLong(String k,long v) { next.put(k,v);return this; }
            public Editor putString(String k,String v) { next.put(k,v);return this; }
            public Editor putBoolean(String k,boolean v) { next.put(k,v);return this; }
            public void apply() { commit(); }
            public boolean commit() { if (fail) return false; values.putAll(next); return true; }
        }; }
    }
    private static void invalid(Context context, Bundle changes) {
        try { Preferences.configure(context, changes); throw new AssertionError("invalid configuration accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] args) {
        Store store = new Store(); Context context = new Context() {
            public SharedPreferences getSharedPreferences(String name,int mode) {
                if (!name.equals("shopping") || mode != MODE_PRIVATE) throw new AssertionError("wrong shared store"); return store;
            }
        };
        check(!Preferences.enabled(context) && !Preferences.classification(context) && !Preferences.vision(context), "all automatic behavior defaults off");
        Preferences.set(context, Preferences.ENABLED, true);
        check(Preferences.read(context).getBoolean("enabled") && !Preferences.classification(context), "GUI change is immediately Agent-readable without enabling AI");
        Bundle api = new Bundle(); api.putBoolean("classification", true); api.putBoolean("vision", true);
        check(Preferences.configure(context, api) && Preferences.classification(context) && Preferences.vision(context), "Agent writes use the same settings read by GUI/runtime");
        Bundle invalidKey = new Bundle(); invalidKey.putBoolean("enabled", false); invalidKey.putBoolean("camera", true);
        invalid(context, invalidKey); check(Preferences.enabled(context), "unknown settings reject the entire mutation");
        Bundle invalidType = new Bundle(); invalidType.putString("vision", "false"); invalid(context, invalidType);
        check(Preferences.vision(context), "string booleans cannot silently change a setting");
        invalid(context, null); invalid(context, new Bundle());
        store.fail = true; Bundle fail = new Bundle(); fail.putBoolean("enabled", false);
        check(!Preferences.configure(context, fail) && Preferences.enabled(context), "failed persistence cannot report success");
        System.out.println(assertions + " shared GUI/Agent preferences assertions passed");
    }
}
