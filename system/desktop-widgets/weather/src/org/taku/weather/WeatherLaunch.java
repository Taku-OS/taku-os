package org.taku.weather;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** One target for the widget, legacy explicit activity, and on-device Agent. */
public final class WeatherLaunch {
    public static final String PACKAGE = "com.google.android.apps.weather";
    public static Intent intent(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch == null) throw new IllegalStateException("PIXEL_WEATHER_UNAVAILABLE");
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    public static JSONObject command(Context context) throws Exception {
        Intent launch = intent(context);
        return new JSONObject().put("effect", "activity-launch-required")
            .put("package", PACKAGE).put("component", launch.getComponent().flattenToShortString())
            .put("agentCommand", "am start -n org.taku.weather/.MainActivity");
    }
}
