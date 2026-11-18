package org.taku.widgets;

/** Preserve the legacy explicit component without publishing a duplicate launcher entry. */
public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try { ClockData.command(this, "clock"); }
        catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        } finally { finish(); }
    }
}
