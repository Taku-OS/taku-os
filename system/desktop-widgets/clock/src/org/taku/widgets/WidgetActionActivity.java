package org.taku.widgets;

/** Foreground dispatch for widget taps and the Agent's returned am-start command. */
public final class WidgetActionActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            String action = getIntent().getAction();
            if (action == null || !action.startsWith(ClockData.COMMAND_PREFIX))
                throw new IllegalArgumentException("INVALID_ARGUMENT: widget command required");
            ClockData.command(this, action.substring(ClockData.COMMAND_PREFIX.length()));
        } catch (Exception error) {
            android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        } finally { finish(); }
    }
}
