package org.agentos.island.fixture;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.agentos.island.IslandContentProtocol;

/** Explicit notification/window test producer. Never part of the ROM or the Assistant session. */
public final class Fixture extends Activity {
    static final String PKG = "org.agentos.island.fixture";
    static final Uri URI = Uri.parse("content://" + PKG);
    private TextView state;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> refresh();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 24, 24, 24);
        column.setBackgroundColor(Color.WHITE);
        column.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(24 + safe.left, 24 + safe.top, 24 + safe.right, 24 + safe.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("Island integration fixture\nReal notifications; no AI session or account required");
        title.setTextSize(22);
        column.addView(title);
        boolean editorOnly = getIntent().getBooleanExtra("editorOnly", false);
        for (String op : editorOnly ? new String[]{} : new String[]{"publish", "publish-v2-memo", "publish-v2-highlight", "publish-live-update", "publish-native-listening", "publish-native-reply", "publish-surface", "publish-alert", "publish-building", "publish-listening", "publish-error", "publish-reply", "cancel", "crash-content"}) {
            Button button = new Button(this);
            button.setText(op);
            button.setOnClickListener(v -> { operation(this, op); refresh(); });
            column.addView(button);
        }
        EditText editor = new EditText(this);
        editor.setHint("IME input test");
        column.addView(editor);
        View gap = new View(this);
        column.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
        state = new TextView(this);
        state.setTextSize(20);
        column.addView(state);
        Button outside = new Button(this);
        outside.setText("Tap outside card");
        outside.setOnClickListener(v -> operation(this, "tap"));
        column.addView(outside);
        setContentView(column);
        if (editorOnly) {
            editor.requestFocus();
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        getWindow().getInsetsController().setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        getSharedPreferences("fixture", 0).registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }
    private void refresh() { if (state != null) state.setText(readState(this).toString()); }
    @Override public void onDestroy() {
        getSharedPreferences("fixture", 0).unregisterOnSharedPreferenceChangeListener(listener);
        super.onDestroy();
    }
    static JSONObject readState(Context c) {
        JSONObject state = new JSONObject();
        try {
            state.put("outsideTaps", c.getSharedPreferences("fixture", 0).getInt("taps", 0));
            state.put("activeNotifications", c.getSystemService(NotificationManager.class).getActiveNotifications().length);
            state.put("lastOperation", c.getSharedPreferences("fixture", 0).getString("operation", "none"));
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
        return state;
    }
    static void operation(Context c, String op) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        SharedPreferences prefs = c.getSharedPreferences("fixture", 0);
        if ("cancel".equals(op)) { nm.cancel(1); nm.cancel(2); }
        else if ("publish-v2-memo".equals(op) || "publish-v2-highlight".equals(op) || "publish-live-update".equals(op)) {
            publishV2(c, nm, op);
        }
        else if ("crash-content".equals(op)) c.startService(new Intent(c, Content.class).setAction(PKG + ".CRASH_CONTENT"));
        else if ("tap".equals(op)) prefs.edit().putInt("taps", prefs.getInt("taps", 0) + 1).apply();
        else if ("reset".equals(op)) { nm.cancelAll(); prefs.edit().clear().apply(); }
        else if ("publish".equals(op) || "publish-native-listening".equals(op) || "publish-native-reply".equals(op) || "publish-surface".equals(op) || "publish-alert".equals(op) || "publish-building".equals(op) || "publish-listening".equals(op) || "publish-error".equals(op) || "publish-reply".equals(op)) {
            nm.createNotificationChannel(new NotificationChannel("island-test", "Island integration tests", NotificationManager.IMPORTANCE_LOW));
            try {
                PendingIntent stop = PendingIntent.getBroadcast(c, 1, new Intent(c, Receiver.class).putExtra("operation", "cancel"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification.Action action = new Notification.Action.Builder(null, "Stop fixture", stop).build();
                JSONObject expanded = new JSONObject().put("template", "progress").put("title", "Island integration fixture")
                    .put("body", "Native fallback: this test notification is still active.")
                    .put("progress", new JSONObject().put("value", 0.4)).put("actions", new JSONArray().put("stop"));
                if ("publish-surface".equals(op)) expanded.put("content", new JSONObject().put("service", PKG + "/" + Content.class.getName()).put("token", "fixture"));
                String label = "publish-building".equals(op) ? "Building..." : "publish-listening".equals(op) ? "Listening..."
                    : "publish-error".equals(op) ? "Couldn't finish" : "publish-reply".equals(op) ? "Reply ready" : "Island test";
                JSONObject payload = new JSONObject().put("version", 2)
                    .put("compact", new JSONObject().put("a", new JSONObject().put("type", "progress").put("value", 0.4))
                        .put("b", new JSONObject().put("type", "text").put("text", label)))
                    .put("expanded", expanded).put("alert", "publish-alert".equals(op));
                if (op.startsWith("publish-native-")) {
                    // The assistant template belongs to Taku Agent: these cases demonstrate rejection.
                    boolean listening = "publish-native-listening".equals(op);
                    payload.remove("compact");
                    expanded.put("template", "assistant").put("title", "Native assistant fixture")
                        .put("body", listening ? "Taku is listening..." : "Agent reply from the current turn.")
                        .put("actions", listening ? new JSONArray() : new JSONArray().put("reply_0"))
                        .put("assistant", new JSONObject().put("token", "native-fixture").put("phase", listening ? "listening" : "message")
                            .put("heightDp", listening ? 96 : 152).put("suggestions", listening ? new JSONArray() : new JSONArray().put("reply_0")));
                    expanded.remove("progress");
                }
                Bundle extras = new Bundle(); extras.putString("taku.island.v2", payload.toString());
                Bundle actions = new Bundle(); actions.putParcelable("stop", action);
                if ("publish-native-reply".equals(op)) {
                    PendingIntent tap = PendingIntent.getBroadcast(c, 3, new Intent(c, Receiver.class).putExtra("operation", "tap"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    actions.putParcelable("reply_0", new Notification.Action.Builder(null, "Show details", tap).build());
                }
                extras.putBundle("taku.island.actions", actions);
                PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, Fixture.class), PendingIntent.FLAG_IMMUTABLE);
                nm.notify(1, new Notification.Builder(c, "island-test").setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("Island integration fixture").setContentText("40% — test notification")
                    .setOngoing(true).setContentIntent(open).addAction(action).addExtras(extras).build());
            } catch (JSONException impossible) { throw new AssertionError(impossible); }
        } else throw new IllegalArgumentException("Unknown fixture operation: " + op);
        prefs.edit().putString("operation", op).apply();
    }
    /**
     * v2 acceptance cases (notification id 2, so they coexist with id 1 for switching/secondary):
     * a voice memo whose capsule tap opens the app, a high-importance highlight card that alerts
     * below the cutout with forty scrolling actions and a card tap action, and a plain Android Live
     * Update with no Taku extras.
     */
    static void publishV2(Context c, NotificationManager nm, String op) {
        nm.createNotificationChannel(new NotificationChannel("island-test", "Island integration tests", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("island-alert", "Island heads-up tests", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, Fixture.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getBroadcast(c, 4, new Intent(c, Receiver.class).putExtra("operation", "cancel"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent tap = PendingIntent.getBroadcast(c, 5, new Intent(c, Receiver.class).putExtra("operation", "tap"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if ("publish-live-update".equals(op)) {
                Bundle promoted = new Bundle();
                promoted.putBoolean("android.requestPromotedOngoing", true);
                nm.notify(2, new Notification.Builder(c, "island-test").setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Delivery on the way").setContentText("Courier is 12 minutes away")
                    .setOngoing(true).setShortCriticalText("12 min").setContentIntent(open)
                    .setStyle(new Notification.ProgressStyle().setProgressSegments(java.util.List.of(new Notification.ProgressStyle.Segment(100))).setProgress(40))
                    .setTimeoutAfter(2 * 60 * 60 * 1000L).addExtras(promoted)
                    .addAction(new Notification.Action.Builder(null, "Cancel delivery", cancel).build()).build());
                return;
            }
            Bundle pics = new Bundle();
            pics.putParcelable("mic", android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_btn_speak_now));
            Bundle actions = new Bundle();
            JSONObject payload;
            String channel = "island-test";
            if ("publish-v2-memo".equals(op)) {
                actions.putParcelable("stop", new Notification.Action.Builder(null, "Stop", cancel).build());
                payload = new JSONObject().put("version", 2).put("tap", "open").put("timeoutS", 7200)
                    .put("compact", new JSONObject()
                        .put("a", new JSONObject().put("type", "icon").put("pic", "mic").put("tint", true))
                        .put("b", new JSONObject().put("type", "timer").put("startEpochMs", System.currentTimeMillis())))
                    .put("expanded", new JSONObject().put("template", "basic").put("title", "Recording voice memo")
                        .put("body", "Fixture recording; Stop cancels this notification.")
                        .put("actions", new JSONArray().put("stop")).put("primaryAction", "stop"));
            } else {
                channel = "island-alert";
                JSONArray ids = new JSONArray();
                for (int i = 0; i < 40; i++) {
                    ids.put("a" + i);
                    actions.putParcelable("a" + i, new Notification.Action.Builder(null, "Option " + (i + 1), tap).build());
                }
                actions.putParcelable("open_order", new Notification.Action.Builder(null, "Open order", tap).build());
                payload = new JSONObject().put("version", 2).put("cardPosition", "below").put("alert", true).put("timeoutS", 5400)
                    .put("compact", new JSONObject()
                        .put("a", new JSONObject().put("type", "icon").put("pic", "mic").put("progress", 0.6))
                        .put("b", new JSONObject().put("type", "text").put("text", "A123").put("emphasis", "accent")))
                    .put("expanded", new JSONObject().put("template", "highlight").put("title", "Order ready")
                        .put("highlight", new JSONObject().put("text", "A123").put("label", "Pickup code"))
                        .put("body", "Counter 3 - fixture data").put("actions", ids).put("primaryAction", "a0")
                        .put("tapAction", "open_order").put("accentColor", "#FF8A3D"));
            }
            Bundle extras = new Bundle();
            extras.putString("taku.island.v2", payload.toString());
            extras.putBundle("taku.island.pics", pics);
            extras.putBundle("taku.island.actions", actions);
            nm.notify(2, new Notification.Builder(c, channel).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("publish-v2-memo".equals(op) ? "Recording voice memo" : "Order ready")
                .setContentText("Island v2 fixture").setOngoing(true).setContentIntent(open).addExtras(extras).build());
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { operation(c, i.getStringExtra("operation")); }
    }
    public static final class State extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"json"}); cursor.addRow(new Object[]{readState(getContext()).toString()}); return cursor;
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            operation(getContext(), method); Bundle result = new Bundle(); result.putString("json", readState(getContext()).toString()); return result;
        }
        @Override public String getType(Uri u) { return "application/json"; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
    public static final class Content extends Service {
        private final Handler main = new Handler(Looper.getMainLooper());
        private SurfaceControlViewHost host;
        private IBinder clientBinder;
        private IBinder.DeathRecipient death;
        private boolean linked;
        private int generation;
        private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) {
                if (m.what == IslandContentProtocol.RELEASE) { if (m.arg1 == generation) release(); return; }
                if (m.what != IslandContentProtocol.ATTACH || m.replyTo == null) return;
                release(); generation = m.arg1;
                Messenger client = m.replyTo; int request = generation;
                try {
                    Bundle b = m.getData(); int w = b.getInt("width"), h = b.getInt("height");
                    if (b.getInt("version") != 1 || !"fixture".equals(b.getString("token")) || w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 4194304) throw new IllegalArgumentException();
                    Display display = getSystemService(DisplayManager.class).getDisplay(b.getInt("display"));
                    Context visual = createDisplayContext(display);
                    host = new SurfaceControlViewHost(visual, display, b.getBinder("hostToken"));
                    TextView view = new TextView(visual);
                    view.setText("LIVE SURFACE\nSeparate fixture content process"); view.setTextSize(24);
                    view.setGravity(Gravity.CENTER); view.setTextColor(Color.WHITE); view.setBackgroundColor(0xff176c72);
                    clientBinder = client.getBinder(); death = () -> main.post(() -> { if (generation == request) release(); });
                    clientBinder.linkToDeath(death, 0);
                    linked = true;
                    host.setView(view, w, h);
                    view.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                        if (host == null || generation != request) return;
                        SurfaceControlViewHost.SurfacePackage surface = host.getSurfacePackage();
                        if (surface == null) return;
                        try {
                            Message ready = Message.obtain(null, IslandContentProtocol.READY); ready.arg1 = request;
                            Bundle data = new Bundle(); data.putParcelable("surface", surface); ready.setData(data); client.send(ready);
                        } catch (RemoteException e) { release(); } finally { surface.release(); }
                    }));
                    view.invalidate();
                } catch (Exception error) {
                    release();
                    try { Message failed = Message.obtain(null, IslandContentProtocol.FAILED); failed.arg1 = request; client.send(failed); } catch (RemoteException ignored) { }
                }
            }
        });
        private void release() {
            if (linked && clientBinder != null && death != null) clientBinder.unlinkToDeath(death, 0);
            linked = false;
            clientBinder = null; death = null;
            if (host != null) host.release(); host = null;
        }
        @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }
        @Override public int onStartCommand(Intent i, int flags, int startId) {
            stopSelf(startId);
            if (i != null && (PKG + ".CRASH_CONTENT").equals(i.getAction())) {
                // A real SIGKILL of this fixture process; no graceful host release callback.
                main.post(() -> android.os.Process.killProcess(android.os.Process.myPid()));
            }
            return START_NOT_STICKY;
        }
        @Override public void onDestroy() { release(); super.onDestroy(); }
    }
}
