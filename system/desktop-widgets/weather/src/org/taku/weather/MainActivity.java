package org.taku.weather;

/** Widget taps always open Pixel Weather; source configuration is a separate entry. */
public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try { startActivity(WeatherLaunch.intent(this)); }
        catch (IllegalStateException | android.content.ActivityNotFoundException error) {
            android.widget.Toast.makeText(this, "Pixel Weather is not installed or is disabled", android.widget.Toast.LENGTH_LONG).show();
        } finally { finish(); }
    }
}
