// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.taku.tryon.data.FalKeyStore;
import org.taku.tryon.data.XmaxKeyStore;

/** Device-local selection. A running preview owns its own immutable snapshot. */
public final class RealtimeCallConfiguration {
    public enum Provider { LUCY, XMAX }
    public static final String GLOBAL = "global", CHINA = "china";
    private static final String PREFERENCES = "realtime-call-configuration";
    static final String COSPLAY_PREFERENCES = "cosplay-settings";
    public final Provider provider;
    public final String environment;

    public RealtimeCallConfiguration(Provider provider, String environment) {
        if (provider == null || !(GLOBAL.equals(environment) || CHINA.equals(environment)))
            throw new IllegalArgumentException("invalid_realtime_configuration");
        this.provider = provider; this.environment = environment;
    }
    static RealtimeCallConfiguration fromStored(String provider, String environment) {
        Provider selected;
        try { selected = Provider.valueOf(provider); }
        catch (RuntimeException invalid) { selected = Provider.LUCY; }
        return new RealtimeCallConfiguration(selected, CHINA.equals(environment) ? CHINA : GLOBAL);
    }
    /** Shopping has its own explicit choice; existing call/character settings never select it implicitly. */
    public static RealtimeCallConfiguration loadShopping(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return fromStored(prefs.getString("shopping_provider", "LUCY"), prefs.getString("shopping_environment", GLOBAL));
    }
    /** Voice character swap keeps its own choice; upgrades retain the previously fixed XMax route. */
    public static RealtimeCallConfiguration loadCosplay(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(COSPLAY_PREFERENCES, Context.MODE_PRIVATE);
        String selected = prefs.getString("cosplay_provider", "XMAX");
        String fallback = loadShopping(context).environment;
        String region = prefs.getString("cosplay_environment", fallback);
        return new RealtimeCallConfiguration("LUCY".equals(selected) ? Provider.LUCY : Provider.XMAX,
                CHINA.equals(region) ? CHINA : GLOBAL);
    }
    public void saveShopping(Context context) throws IOException {
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("shopping_provider", provider.name()).putString("shopping_environment", environment).commit())
            throw new IOException("Could not save the try-on model.");
    }
    public void saveCosplay(Context context) throws IOException {
        if (!context.getSharedPreferences(COSPLAY_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("cosplay_provider", provider.name()).putString("cosplay_environment", environment).commit())
            throw new IOException("Could not save the character model.");
    }
    public String providerId() { return provider == Provider.LUCY ? "lucy" : "xmax"; }
    public String cosplayModelLabel() { return provider == Provider.LUCY ? "Lucy 2.6" : englishLabel(); }
    public boolean hasKey(Context context) {
        return provider == Provider.LUCY ? new FalKeyStore(context).hasKey() : new XmaxKeyStore(context).hasKey();
    }
    public String label() { return provider == Provider.LUCY ? "fal · Lucy" : "XMax · " + (CHINA.equals(environment) ? "中国区" : "全球区"); }
    public String englishLabel() { return provider == Provider.LUCY ? "Lucy" : "XMax · " + (CHINA.equals(environment) ? "China" : "Global"); }
    public String missingKeyMessage() { return "请先在连接设置中保存你的 " + (provider == Provider.LUCY ? "fal" : "XMax") + " Key。"; }
    public String missingShoppingKeyMessage() { return "Save your " + (provider == Provider.LUCY ? "fal" : "XMax") + " Key in Try-on model settings, then confirm try-on again."; }
}
