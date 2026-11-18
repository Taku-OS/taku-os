package org.agentos.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact-thread, view-only JPEG previews. No display creation or input operation. */
final class ChatWorkspacePreview extends LinearLayout {
    private static final long CATALOG_MAX_AGE_MS = ChatWorkspacePreviewProtocol.CATALOG_MAX_AGE_MS;
    private static final long FRAME_MAX_AGE_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Consumer<JSONObject> onOpen;
    private final DrawerTheme theme;
    private JSONObject exactLease;
    private boolean floating;
    private int floatingPreviewLimit;
    private Runnable frameReady;
    private boolean requested;
    private String threadId = "";
    private volatile Session session;
    private final Runnable freshness = new Runnable() {
        @Override public void run() {
            Session active = session;
            if (active == null) return;
            long now = SystemClock.elapsedRealtime();
            for (Card card : cards.values()) {
                if (now - active.catalogAt > CATALOG_MAX_AGE_MS
                        || (card.frameAt != 0 && now - card.frameAt > FRAME_MAX_AGE_MS))
                    card.clear(R.string.chat_workspace_unavailable);
            }
            main.postDelayed(this, 500);
        }
    };

    ChatWorkspacePreview(Context context, DrawerTheme theme) {
        this(context, theme, lease -> {
            Intent intent = new Intent(context, MirrorActivity.class)
                    .putExtra("returnConversationId", lease.optString("threadId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            for (String key : new String[]{"threadId", "screenKey", "generation"})
                intent.putExtra(key, lease.optString(key));
            context.startActivity(intent);
        });
    }

    ChatWorkspacePreview(Context context, DrawerTheme theme, Consumer<JSONObject> onOpen) {
        super(context);
        this.theme = theme;
        this.onOpen = onOpen;
        setOrientation(VERTICAL);
        setClipToPadding(false);
    }

    /** Configure before start. A floating view can never follow a replacement lease. */
    void floatingLease(JSONObject lease) {
        halt();
        try { exactLease = new JSONObject(lease.toString()); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid workspace", invalid); }
        floating = true;
    }

    void setOnFrameReady(Runnable listener) { frameReady = listener; }
    private volatile ChatWorkspacePreviewProtocol.CatalogSnapshot floatingCatalog;
    private TextView floatingPlaceholder;

    /** Reuse the float host's verified read, never a synthetic lease or refreshed timestamp. */
    void acceptFloatingCatalog(String thread, JSONObject catalog, long checkedAt) {
        if (!floating || exactLease == null || !thread.equals(exactLease.optString("threadId"))) return;
        try {
            long now = SystemClock.elapsedRealtime();
            ChatWorkspacePreviewProtocol.CatalogSnapshot verified =
                    new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog, checkedAt, now);
            verified.select(thread, now);
            floatingCatalog = verified;
            start(thread);
        } catch (Exception invalid) { floatingCatalog = null; stop(); }
    }
    String diagnostics() {
        Session current = session;
        return "requested=" + requested + " attached=" + isAttachedToWindow()
                + " shown=" + isShown() + " visibility=" + getWindowVisibility()
                + " size=" + getWidth() + "x" + getHeight() + " cards=" + cards.size()
                + " worker=" + (current != null && current.worker.isAlive())
                + " phase=" + (current == null ? "stopped" : current.phase);
    }
    void setFloatingPreviewLimit(int pixels) {
        pixels = Math.max(1, pixels);
        if (floatingPreviewLimit == pixels) return;
        floatingPreviewLimit = pixels;
        // Keep input and actions in place while a window/IME change reconnects
        // the preview. The reserved rectangle never substitutes a cached frame.
        if (floating) setMinimumHeight(pixels);
        if (floatingPlaceholder != null) {
            floatingPlaceholder.getLayoutParams().height = pixels;
            floatingPlaceholder.requestLayout();
        }
        for (Card card : cards.values()) card.viewport.requestLayout();
    }

    /** UI-thread lifecycle calls. Repeated calls for the current thread are no-ops. */
    void start(String thread) {
        requested = true;
        updateThread(thread);
    }

    void updateThread(String thread) {
        String next = ChatWorkspacePreviewProtocol.validThread(thread) ? thread : "";
        if (!next.equals(threadId)) {
            halt();
            threadId = next;
        }
        reconcileLifecycle();
    }

    void stop() {
        requested = false;
        halt();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reconcileLifecycle();
    }

    @Override protected void onDetachedFromWindow() {
        halt();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Android can invoke this while the View superclass is being constructed.
        if (main != null) reconcileLifecycle();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (main != null) reconcileLifecycle();
    }

    private void reconcileLifecycle() {
        boolean visible = requested && !threadId.isEmpty() && isAttachedToWindow()
                && isShown() && getWindowVisibility() == VISIBLE;
        if (!visible) {
            halt();
        } else if (session == null) {
            Session next = new Session(threadId);
            session = next;
            next.worker.start();
            main.post(freshness);
        }
    }

    private void halt() {
        Session old = session;
        session = null;
        if (old != null) old.close();
        main.removeCallbacks(freshness);
        clearCards();
    }

    private void clearCards() {
        for (Card card : cards.values()) card.clear(R.string.chat_workspace_unavailable);
        cards.clear();
        removeAllViews();
        floatingPlaceholder = null;
        if (floating) showFloatingPlaceholder(R.string.chat_workspace_connecting);
        // No own GONE flag: an empty, zero-height host still discovers a new lease.
    }

    private void showFloatingPlaceholder(int message) {
        if (!floating) return;
        if (floatingPlaceholder == null) {
            floatingPlaceholder = new TextView(getContext());
            floatingPlaceholder.setGravity(Gravity.CENTER);
            floatingPlaceholder.setPadding(dp(12), dp(12), dp(12), dp(12));
            floatingPlaceholder.setTextColor(getContext().getColor(R.color.workspace_viewer_secondary));
            addView(floatingPlaceholder, new LayoutParams(-1, floatingPreviewLimit > 0 ? floatingPreviewLimit : dp(200)));
        }
        floatingPlaceholder.setText(message); ChatDesign.text(floatingPlaceholder, ChatDesign.Role.META);
    }

    private boolean current(Session source) {
        return session == source && !source.closed && requested
                && source.thread.equals(threadId) && isAttachedToWindow()
                && isShown() && getWindowVisibility() == VISIBLE;
    }

    private void catalog(Session source, JSONArray selected) {
        if (!current(source)) return;
        if (selected.length() > 0 && floatingPlaceholder != null) {
            removeView(floatingPlaceholder); floatingPlaceholder = null;
        }
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < selected.length(); i++)
            keep.add(ChatWorkspacePreviewProtocol.identity(selected.optJSONObject(i)));
        for (String key : new ArrayList<>(cards.keySet())) {
            if (!keep.contains(key)) {
                Card removed = cards.remove(key);
                removed.clear(R.string.chat_workspace_unavailable);
                removeView(removed);
            }
        }
        for (int i = 0; i < selected.length(); i++) {
            JSONObject binding = selected.optJSONObject(i);
            String key = ChatWorkspacePreviewProtocol.identity(binding);
            Card card = cards.get(key);
            if (card == null) {
                card = new Card(binding);
                cards.put(key, card);
                LayoutParams params = new LayoutParams(-1, -2);
            params.topMargin = floating ? 0 : dp(12);
            params.bottomMargin = floating ? 0 : dp(4);
                addView(card, params);
            }
            card.bind(binding);
        }
        if (selected.length() == 0) showFloatingPlaceholder(R.string.chat_workspace_unavailable);
    }

    private int dp(float value) { return theme.dp(getContext(), value); }

    private String appName(JSONObject binding) {
        String pkg = binding.optString("packageName");
        try {
            return getContext().getPackageManager().getApplicationLabel(
                    getContext().getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception unavailable) {
            String label = binding.optString("appLabel").trim();
            return !label.isEmpty() ? label : !pkg.isEmpty() ? pkg
                    : getContext().getString(R.string.chat_workspace_app);
        }
    }

    private int stateLabel(JSONObject binding) {
        String owner = binding.optString("controlOwner"), state = binding.optString("status");
        if (state.equals("stopping") || owner.equals("stopping")) return R.string.workspace_status_stopping;
        if (owner.equals("user")) return R.string.workspace_status_user;
        if (owner.equals("paused")) return R.string.workspace_status_paused;
        if (binding.optBoolean("resumeRequired") || state.equals("awaiting_resume"))
            return binding.optBoolean("canResume") ? R.string.workspace_status_resume : R.string.workspace_status_paused;
        if (state.equals("waiting") || state.equals("waiting_for_user") || binding.optBoolean("waitingForUser"))
            return R.string.workspace_status_waiting;
        if (!binding.optString("activeTurnId").isEmpty() || state.equals("running") || state.equals("working"))
            return R.string.workspace_status_working;
        return R.string.workspace_status_ready;
    }

    private final class Card extends LinearLayout {
        final TextView title, status, unavailable;
        final FrameImage image;
        final FrameLayout viewport;
        final View stateDot;
        float frameRatio = 16f / 9f;
        JSONObject binding;
        long frameAt;
        boolean connected;

        Card(JSONObject lease) {
            super(ChatWorkspacePreview.this.getContext());
            binding = lease;
            setOrientation(VERTICAL);
            GradientDrawable shell = new GradientDrawable();
            shell.setColor(getContext().getColor(R.color.taku_action_neutral));
            shell.setCornerRadius(dp(16));
            setBackground(shell);
            setPadding(floating ? 0 : dp(8), floating ? 0 : dp(4), floating ? 0 : dp(8), floating ? 0 : dp(8));
            setFocusable(!floating);
            LinearLayout header = new LinearLayout(getContext());
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(dp(48));
            header.setPadding(dp(4), 0, dp(4), 0);
            stateDot = new View(getContext());
            LayoutParams dotSize = new LayoutParams(dp(8), dp(8)); dotSize.setMarginEnd(dp(6));
            header.addView(stateDot, dotSize);
            title = new TextView(getContext());
            title.setTextColor(getContext().getColor(R.color.taku_text_inverse));
            title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(title, new LayoutParams(0, -2, 1));
            status = new TextView(getContext());
            status.setTextColor(getContext().getColor(R.color.taku_text_inverse));
            status.setMaxLines(1); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams statusSize = new LayoutParams(-2, -2); statusSize.setMarginStart(dp(8));
            header.addView(status, statusSize);
            if (!floating) addView(header, new LayoutParams(-1, -2));
            viewport = new FrameLayout(getContext()) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    if (floating) {
                        int height = floatingPreviewLimit > 0 ? floatingPreviewLimit
                                : Math.round(MeasureSpec.getSize(widthSpec) * 1.25f);
                        heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
                    }
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            GradientDrawable inset = new GradientDrawable();
            inset.setColor(getContext().getColor(R.color.taku_surface_control));
            inset.setCornerRadius(dp(12));
            viewport.setBackground(inset);
            viewport.setClipToOutline(true);
            image = new FrameImage(getContext());
            // The compact card is a read-only crop. Expanding opens the full,
            // uncropped frame in MirrorActivity before any user takeover.
            if (floating) image.cover = true;
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            viewport.addView(image, new FrameLayout.LayoutParams(-1, -1));
            unavailable = new TextView(getContext());
            unavailable.setGravity(Gravity.CENTER);
            unavailable.setTextColor(getContext().getColor(R.color.taku_text_inverse));
            unavailable.setPadding(dp(16), dp(16), dp(16), dp(16));
            unavailable.setText(R.string.chat_workspace_connecting);
            ChatDesign.text(unavailable, ChatDesign.Role.META);
            viewport.addView(unavailable, new FrameLayout.LayoutParams(-1, -1));
            addView(viewport, new LayoutParams(-1, floating ? -2 : dp(190)));
            if (!floating) setOnClickListener(view -> {
                Session active = session;
                if (!connected || active == null || !current(active)
                        || SystemClock.elapsedRealtime() - active.catalogAt > CATALOG_MAX_AGE_MS) return;
                try { onOpen.accept(new JSONObject(binding.toString())); }
                catch (Exception unavailable) {
                    Toast.makeText(getContext(), R.string.chat_workspace_open_failed, Toast.LENGTH_SHORT).show();
                }
            });
            clear(R.string.chat_workspace_connecting);
        }

        void bind(JSONObject next) {
            binding = next;
            title.setText(getContext().getString(R.string.chat_workspace_title, appName(next)));
            ChatDesign.text(title, ChatDesign.Role.CAPTION);
            if (connected) status.setText(compactState(next));
            else status.setText(R.string.chat_workspace_connecting);
            ChatDesign.text(status, ChatDesign.Role.META_MEDIUM);
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL);
            dot.setColor(getContext().getColor(stateColor(next))); stateDot.setBackground(dot);
            setContentDescription(getContext().getString(floating ? R.string.workspace_float_preview : R.string.chat_workspace_open, appName(next))
                    + ". " + status.getText());
        }

        void show(Bitmap bitmap) {
            float ratio = (float) bitmap.getHeight() / bitmap.getWidth();
            if (Math.abs(frameRatio - ratio) > .001f) { frameRatio = ratio; viewport.requestLayout(); }
            image.frame(bitmap);
            frameAt = SystemClock.elapsedRealtime();
            connected = true;
            unavailable.setVisibility(GONE);
            setEnabled(true);
            bind(binding);
            if (frameReady != null) frameReady.run();
        }

        void clear(int message) {
            connected = false;
            frameAt = 0;
            image.setImageDrawable(null);
            unavailable.setText(message);
            ChatDesign.text(unavailable, ChatDesign.Role.META);
            unavailable.setVisibility(VISIBLE);
            status.setText(message);
            ChatDesign.text(status, ChatDesign.Role.META_MEDIUM);
            setContentDescription(getContext().getString(floating ? R.string.workspace_float_preview : R.string.chat_workspace_open, appName(binding))
                    + ". " + getContext().getString(message));
            setEnabled(false);
        }
    }

    static int compactState(JSONObject screen) {
        String owner = screen.optString("controlOwner"), state = screen.optString("status");
        if (owner.equals("user")) return R.string.workspace_float_user;
        if (state.equals("stopping")) return R.string.workspace_float_stopping;
        if (owner.equals("paused") || screen.optBoolean("resumeRequired")) return R.string.workspace_float_paused;
        if (state.equals("waiting_for_user")) return R.string.workspace_float_waiting;
        if (state.equals("unavailable") || state.equals("rebind_required")) return R.string.workspace_float_unavailable_short;
        return !screen.optString("activeTurnId").isEmpty() ? R.string.workspace_float_working : R.string.workspace_float_ready;
    }
    static int stateColor(JSONObject screen) {
        int label = compactState(screen);
        if (label == R.string.workspace_float_working || label == R.string.workspace_float_waiting || label == R.string.workspace_float_user)
            return R.color.taku_status_attention;
        if (label == R.string.workspace_float_unavailable_short) return R.color.taku_status_error;
        return R.color.workspace_viewer_secondary;
    }

    /** A readable top-aligned crop of the actual frame; full-screen preview shows it all. */
    private static final class FrameImage extends ImageView {
        boolean cover;
        FrameImage(Context context) { super(context); setScaleType(ScaleType.MATRIX); }
        void frame(Bitmap bitmap) { setImageBitmap(bitmap); fit(); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            fit();
        }
        private void fit() {
            if (getScaleType() != ScaleType.MATRIX) return;
            if (getDrawable() == null || getDrawable().getIntrinsicWidth() <= 0) return;
            float scale = (float) getWidth() / getDrawable().getIntrinsicWidth();
            if (cover) scale = Math.max(scale, (float) getHeight() / getDrawable().getIntrinsicHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            if (cover) matrix.postTranslate((getWidth() - getDrawable().getIntrinsicWidth() * scale) / 2f, 0);
            setImageMatrix(matrix);
        }
    }

    private final class Session implements Runnable, Closeable {
        final String thread;
        final Thread worker;
        final Set<LocalSocket> sockets = new HashSet<>();
        final Map<String, Channel> channels = new LinkedHashMap<>();
        volatile boolean closed;
        volatile long catalogAt;
        volatile String phase = "starting";

        Session(String thread) {
            this.thread = thread;
            worker = new Thread(this, "chat-workspace-preview");
        }

        synchronized void track(LocalSocket socket) throws IOException {
            if (closed) { socket.close(); throw new IOException("PREVIEW_STOPPED"); }
            sockets.add(socket);
        }

        synchronized void untrack(LocalSocket socket) { sockets.remove(socket); }

        @Override public void close() {
            ArrayList<LocalSocket> close;
            synchronized (this) { closed = true; close = new ArrayList<>(sockets); sockets.clear(); }
            for (LocalSocket socket : close) try { socket.close(); } catch (IOException ignored) {}
            worker.interrupt();
        }

        @Override public void run() {
            try {
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    try {
                        phase = "listing";
                        JSONArray selected;
                        long checkedAt;
                        if (floating) {
                            ChatWorkspacePreviewProtocol.CatalogSnapshot snapshot = floatingCatalog;
                            if (snapshot == null) throw new IOException("CATALOG_EXPIRED");
                            selected = snapshot.select(thread, SystemClock.elapsedRealtime());
                            checkedAt = snapshot.checkedAt;
                        } else {
                            JSONObject latest = list(); checkedAt = SystemClock.elapsedRealtime();
                            selected = ChatWorkspacePreviewProtocol.select(latest, thread);
                        }
                        if (exactLease != null) {
                            JSONArray exact = new JSONArray();
                            for (int i = 0; i < selected.length(); i++)
                                if (WorkspaceUi.same(exactLease, selected.optJSONObject(i))) exact.put(selected.getJSONObject(i));
                            selected = exact;
                        }
                        final JSONArray catalog = selected;
                        phase = "selected:" + selected.length();
                        catalogAt = checkedAt;
                        Set<String> keep = new HashSet<>();
                        for (int i = 0; i < selected.length(); i++)
                            keep.add(ChatWorkspacePreviewProtocol.identity(selected.getJSONObject(i)));
                        for (String key : new ArrayList<>(channels.keySet()))
                            if (!keep.contains(key)) channels.remove(key).close();
                        main.post(() -> catalog(this, catalog));
                        for (int i = 0; i < selected.length() && !closed; i++) {
                            JSONObject binding = selected.getJSONObject(i);
                            String key = ChatWorkspacePreviewProtocol.identity(binding);
                            try {
                                phase = "reading-frame";
                                Channel channel = channels.get(key);
                                if (channel == null) {
                                    channel = new Channel(this, binding);
                                    channels.put(key, channel);
                                }
                                channel.read(binding);
                                phase = "frame-received";
                            } catch (Exception disconnected) {
                                phase = "frame-error:" + disconnected.getClass().getSimpleName();
                                Channel channel = channels.remove(key);
                                if (channel != null) channel.close();
                                main.post(() -> {
                                    if (!current(this)) return;
                                    Card card = cards.get(key);
                                    if (card != null) card.clear(R.string.chat_workspace_unavailable);
                                });
                            }
                        }
                    } catch (Exception unavailable) {
                        phase = "catalog-error:" + unavailable.getClass().getSimpleName();
                        catalogAt = 0;
                        for (Channel channel : channels.values()) channel.close();
                        channels.clear();
                        main.post(() -> { if (current(this)) clearCards(); });
                    }
                    if (!closed) Thread.sleep(800);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                for (Channel channel : channels.values()) channel.close();
                channels.clear();
            }
        }

        private JSONObject list() throws Exception {
            LocalSocket socket = new LocalSocket();
            track(socket);
            try (LocalSocket connection = socket) {
                connection.connect(new LocalSocketAddress("taku-agent-native", LocalSocketAddress.Namespace.ABSTRACT));
                if (connection.getPeerCredentials().getUid() != 0) throw new IOException("BROKER_IDENTITY");
                connection.setSoTimeout(4000);
                connection.getOutputStream().write(WorkspaceUi.request("list", null).toString().getBytes(StandardCharsets.UTF_8));
                connection.shutdownOutput();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = connection.getInputStream().read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                    if (closed || output.size() > 6_000_000) throw new IOException("INVALID_WORKSPACE_RESPONSE");
                }
                JSONObject answer = new JSONObject(output.toString("UTF-8"));
                if (!answer.optBoolean("ok")) throw new IOException("WORKSPACE_UNAVAILABLE");
                return answer.getJSONObject("result");
            } finally { untrack(socket); }
        }
    }

    private final class Channel implements Closeable {
        final Session owner;
        final LocalSocket socket = new LocalSocket();
        final String identity;
        DataInputStream input;
        volatile boolean closed, awaitingUi;

        Channel(Session owner, JSONObject binding) throws Exception {
            this.owner = owner;
            identity = ChatWorkspacePreviewProtocol.identity(binding);
            owner.track(socket);
            try {
                socket.connect(new LocalSocketAddress("taku-mirror-" + binding.getString("screenKey"),
                        LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(2500);
                input = new DataInputStream(socket.getInputStream());
                validate(binding);
            } catch (Exception failure) { close(); throw failure; }
        }

        private JSONObject validate(JSONObject binding) throws Exception {
            JSONObject meta = new JSONObject(new String(call("status", 65536), StandardCharsets.UTF_8));
            Credentials peer = socket.getPeerCredentials();
            ChatWorkspacePreviewProtocol.validateHost(binding, meta, peer.getUid(), peer.getPid());
            return meta;
        }

        private byte[] call(String command, int maxBytes) throws Exception {
            if (closed || owner.closed) throw new IOException("PREVIEW_STOPPED");
            socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            int size = input.readInt();
            if (size < 0 || size > maxBytes) throw new IOException("INVALID_FRAME_SIZE");
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            return bytes;
        }

        void read(JSONObject binding) throws Exception {
            JSONObject meta = validate(binding);
            if (awaitingUi) return;
            if (SystemClock.elapsedRealtime() - owner.catalogAt > CATALOG_MAX_AGE_MS)
                throw new IOException("SCREEN_LEASE_EXPIRED");
            byte[] jpeg = call("frame", ChatWorkspacePreviewProtocol.MAX_FRAME_BYTES);
            if (jpeg.length < 3 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216)
                throw new IOException("NO_JPEG_FRAME");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (options.outWidth != meta.getInt("width") || options.outHeight != meta.getInt("height"))
                throw new IOException("FRAME_DIMENSIONS_CHANGED");
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 720 || options.outHeight / options.inSampleSize > 1280)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) throw new IOException("INVALID_JPEG_FRAME");
            awaitingUi = true;
            main.post(() -> {
                try {
                    Card card = cards.get(identity);
                    if (!closed && current(owner) && card != null && WorkspaceUi.same(binding, card.binding)
                            && SystemClock.elapsedRealtime() - owner.catalogAt <= CATALOG_MAX_AGE_MS)
                        card.show(bitmap);
                    else bitmap.recycle();
                } finally { awaitingUi = false; }
            });
        }

        @Override public void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            owner.untrack(socket);
        }
    }
}
