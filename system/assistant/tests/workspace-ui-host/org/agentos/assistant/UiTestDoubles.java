package org.agentos.assistant;

import android.app.Activity;
import android.widget.*;
import org.json.JSONObject;

/** Rendering/navigation doubles are never called by these logic-only checks. */
final class WorkspaceStyle {
    final boolean dark = false;
    final int surface = 0, border = 0, muted = 0;
    WorkspaceStyle(boolean dark) {}
}
final class WorkspaceWidgets {
    WorkspaceWidgets(Activity activity, WorkspaceStyle style) {}
    LinearLayout column() { throw new AssertionError("Rendering requires a device"); }
    int dp(int value) { throw new AssertionError("Rendering requires a device"); }
    TextView text(String value, int size, boolean bold) { throw new AssertionError("Rendering requires a device"); }
    void add(LinearLayout parent, android.view.View view, int spacing) { throw new AssertionError("Rendering requires a device"); }
}
final class AgentRepository { static boolean validId(String value) { return value != null; } }
final class PanelActivity extends Activity {}
final class MirrorActivity extends Activity {}
final class WorkspaceDetailActivity extends Activity {}
final class NativeClient {
    interface Callback { void ready(JSONObject value); void failed(String error); }
    static void async(JSONObject request, Callback callback) { throw new AssertionError("No native/device connection in a host test"); }
}
