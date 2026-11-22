// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.HandshakeState;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.JavaI420Buffer;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One explicitly started native fal session. No camera acquisition, microphone,
 * Activity, local server, ADB, permanent key, reconnect or pixel persistence.
 * Signaling honors the Android system proxy; no host-side tunnel is involved.
 * Callers own consent, foreground service lifecycle, source lease and camera return.
 */
public final class CallTryOnRtc implements RealtimeCallTransport {
    public interface Listener {
        void onState(String state);
        void onRemoteFrame(byte[] jpeg, int width, int height);
        void onFailure(String code);
        default void onDiagnostic(Map<String, Object> snapshot) {}
        default void onCleanup(boolean confirmed) {}
    }
    public interface UpdateListener { void onComplete(String code); }
    private static boolean initialized;
    private final Context context;
    private final Listener listener;
    private final FalRealtimeProfile profile;
    private final CallTryOnRtcDiagnostics diagnostics = new CallTryOnRtcDiagnostics();
    private final AtomicBoolean statsScheduled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean(), stopped = new AtomicBoolean();
    private final AtomicBoolean inputBusy = new AtomicBoolean(), outputBusy = new AtomicBoolean(), firstFrame = new AtomicBoolean();
    private final AtomicInteger inboundPending = new AtomicInteger();
    private static final int SOURCE_NEW = 0, SOURCE_STARTING = 1, SOURCE_READY = 2, SOURCE_CLOSED = 3;
    // One-way gate: late initialization can never publish READY over CLOSED.
    private final AtomicInteger sourceState = new AtomicInteger(SOURCE_NEW);
    private final Object sourceLock = new Object(), socketLock = new Object(), signalIngressLock = new Object();
    private final HandlerThread controlThread;
    private final Handler control;
    private final ExecutorService input = Executors.newSingleThreadExecutor(r -> new Thread(r, "tryon-rtc-input"));
    private final ExecutorService output = Executors.newSingleThreadExecutor(r -> new Thread(r, "tryon-rtc-return"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "tryon-rtc-deadline"));
    private final ArrayDeque<Map<String, Object>> messages = new ArrayDeque<>();
    // The actor owns the deque; socket readers observe this numeric diagnostic mirror only.
    private volatile int decodedMessageCount;
    private volatile String processingType = "none";
    private final ArrayList<IceCandidate> candidates = new ArrayList<>();
    private final FalModelUpdate updates = new FalModelUpdate();
    // Serialized by updates; input/output frame workers never take this lock.
    private PendingUpdate pendingUpdate;
    private String activeReference;
    private volatile WebSocketClient socket;
    private volatile FalSignalingSocketFactory signalingTransport;
    // Control actor owns all fields below except videoSource, guarded by sourceLock.
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoSource videoSource;
    private VideoTrack localTrack, remoteTrack;
    private boolean processing, remoteDescription;
    private int restarts;
    private long lastSourceNs;
    private volatile long startedElapsedMs;
    private Runnable readyGrace, peerFailureGrace;
    private final VideoSink remoteSink = this::receiveFrame;

    public CallTryOnRtc(Context context, Listener listener) {
        this(context, listener, FalRealtimeProfile.LEGACY_VTON);
    }
    public CallTryOnRtc(Context context, Listener listener, FalRealtimeProfile profile) {
        if (context == null || listener == null || profile == null) throw new IllegalArgumentException("invalid_rtc_arguments");
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.profile = profile;
        controlThread = new HandlerThread("tryon-rtc-control");
        controlThread.start();
        control = new Handler(controlThread.getLooper());
    }

    /** Single-use/idempotent, including validation failures. No automatic paid retries. */
    public void start(String jwt, String prompt, String referenceDataUriOrNull) {
        if (stopped.get() || !started.compareAndSet(false, true)) return;
        if (!sourceState.compareAndSet(SOURCE_NEW, SOURCE_STARTING)) return;
        startedElapsedMs = SystemClock.elapsedRealtime();
        diagnostics.begin(); notifyDiagnostic();
        final URI address;
        final Map<String, Object> initial;
        try {
            address = FalSignalPolicy.signalUri(jwt, profile);
            initial = FalSignalPolicy.initial(prompt, referenceDataUriOrNull, profile);
            diagnostics.initialReferenceProvided(referenceDataUriOrNull != null);
            synchronized (updates) {
                if (stopped.get()) return;
                activeReference = referenceDataUriOrNull;
            }
        } catch (RuntimeException failure) { diagnostics.exception(failure); terminate("invalid_request"); return; }
        // Independent of actor/native SDP work: renegotiation cannot reset budgets.
        try {
            watchdog.schedule(() -> terminate("session_limit"), FalSignalPolicy.SESSION_MS, TimeUnit.MILLISECONDS);
            watchdog.schedule(() -> { if (!firstFrame.get()) terminate("first_frame_timeout"); },
                    FalSignalPolicy.FIRST_FRAME_MS, TimeUnit.MILLISECONDS);
            watchdog.scheduleWithFixedDelay(this::diagnosticCheckpoint, CallTryOnRtcDiagnostics.CHECKPOINT_MS,
                    CallTryOnRtcDiagnostics.CHECKPOINT_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stoppedDuringStart) {
            if (!stopped.get()) terminate("rtc_internal_error");
            return;
        }
        post(() -> {
            if (!active()) return;
            recordInitialInputMetadata(diagnostics, prompt, referenceDataUriOrNull, CallTryOnRtc::referenceDimensions);
            open(address, initial);
        });
    }

    interface ReferenceBoundsReader { int[] read(byte[] encoded); }

    /** Called by the control worker after policy validation. Diagnostics never alter or retain the reference. */
    static void recordInitialInputMetadata(CallTryOnRtcDiagnostics target, String prompt, String reference,
                                           ReferenceBoundsReader reader) {
        int chars = prompt == null ? -1 : prompt.length();
        if (reference == null) { target.initialInputMetadata(chars, 0, 0, 0); return; }
        byte[] encoded = null;
        int width = -1, height = -1, jpegBytes = -1;
        try {
            String jpegPrefix = "data:image/jpeg;base64,";
            boolean jpeg = reference.startsWith(jpegPrefix);
            if (reference.length() > 500 * 1024 || !(jpeg || reference.startsWith("data:image/png;base64,")
                    || reference.startsWith("data:image/webp;base64,"))) return;
            encoded = java.util.Base64.getDecoder().decode(reference.substring(reference.indexOf(',') + 1));
            // PNG/WebP remain supported by the protocol, but their sizes are never labelled JPEG bytes.
            if (jpeg) jpegBytes = encoded.length;
            int[] dimensions = reader.read(encoded);
            if (dimensions != null && dimensions.length == 2 && dimensions[0] > 0 && dimensions[1] > 0) {
                width = dimensions[0]; height = dimensions[1];
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            // Optional input metadata must not change signaling or fabricate a transport failure.
        } finally {
            if (encoded != null) java.util.Arrays.fill(encoded, (byte) 0);
            target.initialInputMetadata(chars, width, height, jpegBytes);
        }
    }

    private static int[] referenceDimensions(byte[] encoded) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        return new int[]{options.outWidth, options.outHeight};
    }

    /** Reserves one update; completion requires real server ACKs, never just send(). */
    public boolean update(String prompt, String referenceDataUriOrNull, UpdateListener completion) {
        if (!profile.runtimeUpdates || completion == null) return false;
        synchronized (updates) {
            WebSocketClient current = socket;
            if (!active() || !firstFrame.get() || current == null || !current.isOpen() || pendingUpdate != null ||
                    !"ready".equals(updates.availability())) return false;
            boolean imageChanged = !java.util.Objects.equals(activeReference, referenceDataUriOrNull);
            final Map<String, Object> payload;
            try { payload = FalSignalPolicy.update(prompt, referenceDataUriOrNull, imageChanged); }
            catch (RuntimeException invalid) { return false; }
            PendingUpdate accepted = new PendingUpdate(referenceDataUriOrNull, completion);
            pendingUpdate = accepted;
            if (!control.post(() -> {
                synchronized (updates) {
                    if (!active() || pendingUpdate != accepted) return;
                    // Reserve the wire generation only here: ACKs already queued
                    // before this actor task must not confirm an unsent update.
                    accepted.id = updates.begin(SystemClock.elapsedRealtime(), imageChanged);
                    if (accepted.id == 0) { completeUpdate(accepted, "update_unconfirmed"); return; }
                    try {
                        if (!send(payload)) {
                            if (updates.fail(accepted.id)) completeUpdate(accepted, "update_unconfirmed");
                            return;
                        }
                        accepted.timeout = () -> {
                            synchronized (updates) {
                                if (updates.expires(accepted.id, SystemClock.elapsedRealtime()))
                                    completeUpdate(accepted, "update_timeout");
                            }
                        };
                        control.postDelayed(accepted.timeout, FalModelUpdate.TIMEOUT_MS);
                    } catch (RuntimeException failure) {
                        if (updates.fail(accepted.id)) completeUpdate(accepted, "update_unconfirmed");
                    }
                }
            })) {
                pendingUpdate = null; return false;
            }
            return true;
        }
    }
    public String updateAvailability() {
        // Lucy 2.6 has only been validated with its initial reference, not live control updates.
        if (!profile.runtimeUpdates) return "restart_required";
        synchronized (updates) { return pendingUpdate == null ? updates.availability() : "pending"; }
    }

    // All completions run on the existing signaling actor. No SDP, peer, token,
    // watchdog or frame worker is restarted or extended by an update.
    private void acknowledgeUpdate(Map<String, Object> message) {
        synchronized (updates) {
            FalModelUpdate.Ack result = updates.acknowledge(FalSignalPolicy.type(message), message.get("success"));
            if (result == FalModelUpdate.Ack.INITIAL_REJECTED) { diagnostics.serverError(message, "control"); terminate("model_control_rejected"); return; }
            PendingUpdate pending = pendingUpdate;
            if (pending == null) return;
            if (result == FalModelUpdate.Ack.APPLIED) {
                activeReference = pending.reference;
                completeUpdate(pending, "updated");
            } else if (result == FalModelUpdate.Ack.REJECTED) completeUpdate(pending, "update_rejected");
            else if (result == FalModelUpdate.Ack.UNCONFIRMED) completeUpdate(pending, "update_unconfirmed");
        }
    }
    private void completeUpdate(PendingUpdate pending, String code) {
        if (pendingUpdate != pending) return;
        pendingUpdate = null;
        if (pending.timeout != null) control.removeCallbacks(pending.timeout);
        if (active()) try { pending.listener.onComplete(code); }
        catch (RuntimeException ignored) { terminate("listener_failed"); }
    }
    private static final class PendingUpdate {
        long id; final String reference; final UpdateListener listener;
        Runnable timeout;
        PendingUpdate(String reference, UpdateListener listener) {
            this.reference = reference; this.listener = listener;
        }
    }

    /** Busy drops immediately; JPEG ownership is copied only for an accepted frame. */
    public void sendFrame(byte[] jpeg, int width, int height, long copiedNs) {
        if (!active() || sourceState.get() != SOURCE_READY || jpeg == null || jpeg.length < 4 || jpeg.length > FalSignalPolicy.MAX_JPEG
                || width < 1 || width > FalSignalPolicy.MAX_SOURCE_EDGE || height < 1 || height > FalSignalPolicy.MAX_SOURCE_EDGE
                || (long) width * height > FalSignalPolicy.MAX_SOURCE_PIXELS
                || !inputBusy.compareAndSet(false, true)) return;
        byte[] owned = jpeg.clone();
        try {
            input.execute(() -> {
                try { if (active()) sendJpeg(owned, width, height, copiedNs); }
                catch (RuntimeException | LinkageError | OutOfMemoryError failure) { diagnostics.exception(failure); terminate("input_frame_failed"); }
                finally { inputBusy.set(false); }
            });
        } catch (RejectedExecutionException failure) { inputBusy.set(false); }
    }

    public void stop() { terminate(null); }
    /** Immutable flat evidence only: no credentials, signaling payloads, references or camera pixels. */
    public Map<String, Object> diagnosticSnapshot() { return diagnostics.snapshot(); }
    private void notifyDiagnostic() {
        try { listener.onDiagnostic(diagnostics.snapshot()); } catch (RuntimeException | LinkageError ignored) { /* Diagnostic IO cannot fail a session. */ }
    }
    private void diagnosticCheckpoint() {
        if (!active()) return;
        WebSocketClient currentSocket = socket;
        if (currentSocket != null) recordSignalQueue(currentSocket);
        notifyDiagnostic();
        if (!statsScheduled.compareAndSet(false, true)) return;
        if (!control.post(() -> {
            PeerConnection current = peer;
            if (!active() || current == null || !diagnostics.beginStats(SystemClock.elapsedRealtime())) {
                statsScheduled.set(false); return;
            }
            try {
                current.getStats(report -> {
                    try {
                        if (active()) diagnostics.completeStats(safeStats(report));
                    } catch (RuntimeException | LinkageError failure) {
                        diagnostics.failStats(failure);
                    } finally { statsScheduled.set(false); }
                });
            } catch (RuntimeException | LinkageError failure) {
                diagnostics.failStats(failure); statsScheduled.set(false);
            }
        })) statsScheduled.set(false);
    }
    private Map<String, Number> safeStats(RTCStatsReport report) {
        Map<String, Number> result = new LinkedHashMap<>();
        if (report == null || report.getStatsMap() == null) throw new IllegalArgumentException("stats_unavailable");
        Map<String, RTCStats> entries = report.getStatsMap();
        Map<String, Long> rtp = new LinkedHashMap<>();
        String selectedPair = null;
        int inspected = 0, inboundRtpReports = 0, inboundVideoReports = 0;
        for (RTCStats stat : entries.values()) {
            if (++inspected > 256) break;
            if (stat == null || stat.getMembers() == null) continue;
            Map<String, Object> members = stat.getMembers();
            if ("inbound-rtp".equals(stat.getType())) {
                inboundRtpReports++;
                if (videoStats(members)) inboundVideoReports++;
            }
            CallTryOnRtcDiagnostics.collectStats(rtp, stat.getType(), members);
            if ("outbound-rtp".equals(stat.getType()) && videoStats(members)) {
                Object codecId = members.get("codecId");
                RTCStats codec = codecId instanceof String ? entries.get(codecId) : null;
                if (codec != null && "codec".equals(codec.getType()) && codec.getMembers() != null) {
                    Object mime = codec.getMembers().get("mimeType");
                    if (mime instanceof String) diagnostics.outboundCodec((String) mime);
                }
            } else if ("remote-inbound-rtp".equals(stat.getType())) {
                // Opaque report IDs stay in this method; only fixed numeric fields leave it.
                Object localId = members.get("localId");
                RTCStats local = localId instanceof String ? entries.get(localId) : null;
                if (videoStats(members)) CallTryOnRtcDiagnostics.collectRemoteInboundStats(result, members);
                else if (local != null && "outbound-rtp".equals(local.getType())
                        && local.getMembers() != null && videoStats(local.getMembers())) {
                    Map<String, Object> video = new LinkedHashMap<>(members);
                    video.put("kind", "video");
                    CallTryOnRtcDiagnostics.collectRemoteInboundStats(result, video);
                }
            } else if ("transport".equals(stat.getType()) && members.get("selectedCandidatePairId") instanceof String) {
                selectedPair = (String) members.get("selectedCandidatePairId");
            }
        }
        diagnostics.statsReport(rtcElapsedMs(), inboundRtpReports, inboundVideoReports,
                Math.min(inspected, 256), inspected > 256);
        RTCStats pair = selectedPair == null ? null : entries.get(selectedPair);
        if (pair != null && "candidate-pair".equals(pair.getType()) && pair.getMembers() != null) {
            CallTryOnRtcDiagnostics.collectSelectedCandidatePairStats(result, pair.getMembers());
        }
        result.putAll(rtp);
        return result;
    }
    private static boolean videoStats(Map<String, Object> members) {
        return "video".equals(members.get("kind")) || "video".equals(members.get("mediaType"));
    }
    private boolean active() { return started.get() && !stopped.get(); }
    private long rtcElapsedMs() { return Math.max(0, SystemClock.elapsedRealtime() - startedElapsedMs); }
    private void post(Runnable task) {
        if (!stopped.get()) control.post(() -> { if (!stopped.get()) {
            try { task.run(); } catch (RuntimeException | LinkageError failure) { diagnostics.exception(failure); terminate("rtc_internal_error"); }
        }});
    }
    private void state(String value) {
        if (!active()) return;
        if ("live".equals(value)) diagnostics.stage("video_live");
        else diagnostics.stage(value);
        if ("media_connected".equals(value)) diagnostics.mediaConnectedAt(rtcElapsedMs());
        notifyDiagnostic();
        if (active()) try { listener.onState(value); } catch (RuntimeException ignored) { terminate("listener_failed"); }
    }
    private static synchronized void initialize(Context context) {
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .setInjectableLogger((message, severity, tag) -> { /* Never log signaling or credentials. */ }, Logging.Severity.LS_VERBOSE)
                    .createInitializationOptions());
            initialized = true;
        }
    }
    private void open(URI address, Map<String, Object> initial) {
        initialize(context);
        if (!active()) return;
        factory = PeerConnectionFactory.builder().setVideoEncoderFactory(new SoftwareVideoEncoderFactory())
                .setVideoDecoderFactory(new SoftwareVideoDecoderFactory()).createPeerConnectionFactory();
        synchronized (sourceLock) {
            if (!active()) return;
            videoSource = factory.createVideoSource(false);
            // Do not adapt to a fixed aspect ratio; that would crop portrait inputs.
            videoSource.getCapturerObserver().onCapturerStarted(true);
            localTrack = factory.createVideoTrack("taku-vton-video", videoSource);
            if (!publishSourceReady()) return;
        }
        if (!active()) return;
        state("connecting");
        WebSocketClient created = new WebSocketClient(address, new DiagnosticDraft(diagnostics), null, 15000) {
            @Override public void onOpen(ServerHandshake handshake) {
                if (!active()) return;
                FalSignalingSocketFactory transport = signalingTransport;
                try {
                    if (transport != null && !transport.markOpened()) {
                        diagnostics.websocketException(transport.timedOut()
                                ? new java.net.SocketTimeoutException() : new java.net.SocketException(), false);
                        terminate("websocket_error"); return;
                    }
                } catch (Exception failure) {
                    diagnostics.websocketException(failure, false); terminate("websocket_error"); return;
                }
                diagnostics.transportStage("connected");
                post(() -> {
                    diagnostics.handshake(handshake.getHttpStatus()); diagnostics.websocketOpened();
                    state("signaling_connected");
                    boolean sent = CallTryOnRtc.this.send(initial, true);
                    diagnostics.initialSendResult(sent);
                    notifyDiagnostic();
                });
            }
            @Override public void onMessage(ByteBuffer data) { receiveSignal(data); }
            @Override public void onMessage(String text) { receiveSignal(text); }
            @Override public void onClose(int code, String reason, boolean remote) { signalClosed(code, remote); }
            @Override public void onError(Exception error) { signalFailed(error); }
            @Override protected void onSetSSLParameters(javax.net.ssl.SSLParameters parameters) {
                super.onSetSSLParameters(parameters);
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
            }
        };
        // SLF4J NOP provider is bundled. WebRTC logger is explicitly discarded above.
        created.setConnectionLostTimeout(15);
        final FalSignalingSocketFactory transport;
        try {
            Proxy proxy = FalSignalingSocketFactory.selectProxy();
            diagnostics.route(FalSignalingSocketFactory.route(proxy));
            if (proxy.type() == Proxy.Type.HTTP) {
                transport = new FalSignalingSocketFactory(proxy, new FalSignalingSocketFactory.Observer() {
                    @Override public void onRoute(String route) { diagnostics.route(route); }
                    @Override public void onProxyConnectStatus(int status) { diagnostics.proxyConnectStatus(status); }
                    @Override public void onConnectionStage(String stage) { diagnostics.transportStage(stage); }
                });
                created.setSocketFactory(transport);
            } else {
                transport = null;
                if (proxy.type() == Proxy.Type.SOCKS) created.setProxy(proxy);
            }
        } catch (RuntimeException failure) {
            diagnostics.websocketException(failure, false); terminate("websocket_error"); return;
        }
        synchronized (socketLock) {
            if (!active()) { if (transport != null) transport.cancel(); return; }
            signalingTransport = transport;
            socket = created;
            created.connect();
        }
    }
    // Decode on the reader. Informational traffic cannot consume the bounded control actor.
    // The ingress lock also keeps a close/error callback behind an earlier decode and control post.
    void receiveSignal(ByteBuffer data) {
        synchronized (signalIngressLock) {
            if (data == null || data.remaining() > FalSignalCodec.MAX_BYTES) { terminate("invalid_signal"); return; }
            if (!active()) return;
            byte[] bytes = new byte[data.remaining()]; data.get(bytes);
            try { acceptDecodedSignal(asMap(FalSignalCodec.decode(bytes))); }
            catch (RuntimeException failure) { queueInvalidSignal(); }
        }
    }

    void receiveSignal(String text) {
        synchronized (signalIngressLock) {
            if (text == null || text.length() > FalSignalCodec.MAX_BYTES) { terminate("invalid_signal"); return; }
            if (!active()) return;
            try { acceptDecodedSignal(asMap(json(new JSONObject(text), 0))); }
            catch (Exception failure) { queueInvalidSignal(); }
        }
    }
    private void acceptDecodedSignal(Map<String, Object> message) {
        if (!active()) return;
        String type = FalSignalPolicy.type(message);
        if ("x-fal-message".equals(type) && FalSignalPolicy.terminalCode(message) == null) {
            recordSignalReceived(type); return;
        }
        if (!acceptInbound()) return;
        control.post(() -> {
            inboundPending.decrementAndGet();
            if (active()) try { enqueue(message); }
            catch (RuntimeException failure) { diagnostics.protocolFailure(); terminate("invalid_signal"); }
        });
    }
    private void queueInvalidSignal() {
        if (!acceptInbound()) return;
        control.post(() -> {
            inboundPending.decrementAndGet();
            if (active()) { diagnostics.protocolFailure(); terminate("invalid_signal"); }
        });
    }

    void signalClosed(int code, boolean remote) {
        // The reader may have queued a final server error immediately before close.
        // Keep FIFO with its decoder; do not delete it from another thread.
        synchronized (signalIngressLock) { post(() -> {
            diagnostics.websocketClosed(code, remote);
            FalSignalingSocketFactory transport = signalingTransport;
            if (transport != null && transport.timedOut()) {
                diagnostics.websocketException(new java.net.SocketTimeoutException(), false);
                terminate("websocket_error");
            } else terminate("websocket_closed");
        }); }
    }

    void signalFailed(Exception error) {
        synchronized (signalIngressLock) { post(() -> {
            FalSignalingSocketFactory transport = signalingTransport;
            if (transport != null && transport.timedOut())
                diagnostics.websocketException(new java.net.SocketTimeoutException(), false);
            else diagnostics.websocketException(error, error instanceof org.java_websocket.exceptions.InvalidDataException);
            terminate("websocket_error");
        }); }
    }

    /** Observe the actual parsed HTTP status, including rejection; keep the same draft acceptance rules. */
    static final class DiagnosticDraft extends Draft_6455 {
        private final CallTryOnRtcDiagnostics diagnostics;
        DiagnosticDraft(CallTryOnRtcDiagnostics diagnostics) { super(List.of(), FalSignalCodec.MAX_BYTES); this.diagnostics = diagnostics; }
        @Override public HandshakeState acceptHandshakeAsClient(ClientHandshake request, ServerHandshake response) throws InvalidHandshakeException {
            diagnostics.handshake(response.getHttpStatus());
            return super.acceptHandshakeAsClient(request, response);
        }
        @Override public Draft copyInstance() { return new DiagnosticDraft(diagnostics); }
    }
    private boolean publishSourceReady() {
        synchronized (sourceLock) {
            // Native initialization, source and CapturerObserver setup precede this release.
            // Stop closes the same atomic gate without waiting on a native call.
            return active() && videoSource != null && localTrack != null
                    && sourceState.compareAndSet(SOURCE_STARTING, SOURCE_READY);
        }
    }
    private boolean acceptInbound() {
        if (!active()) return false;
        int pending = inboundPending.incrementAndGet();
        if (pending > 8) {
            inboundPending.decrementAndGet();
            terminateBackpressure("inbound_pending", pending, -1, -1); return false;
        }
        return true;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("invalid_signal");
        return (Map<String, Object>) value;
    }
    private static Object json(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("invalid_signal");
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            if (source.length() > 16384) throw new IllegalArgumentException("invalid_signal");
            Map<String, Object> result = FalSignalPolicy.map();
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, json(source.get(key), depth + 1)); }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            if (source.length() > 16384) throw new IllegalArgumentException("invalid_signal");
            List<Object> result = new ArrayList<>();
            for (int n = 0; n < source.length(); n++) result.add(json(source.get(n), depth + 1));
            return result;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new IllegalArgumentException("invalid_signal");
    }
    private static long queuedSignalBytes(WebSocketClient current) {
        org.java_websocket.WebSocketImpl connection = (org.java_websocket.WebSocketImpl) current.getConnection();
        long queued = 0;
        for (ByteBuffer buffer : connection.outQueue) queued += buffer.remaining();
        return queued;
    }
    private void recordSignalQueue(WebSocketClient current) {
        try { diagnostics.signalQueueBytes(queuedSignalBytes(current)); }
        catch (RuntimeException | LinkageError ignored) { /* Optional telemetry cannot stop a session. */ }
    }
    private boolean send(Map<String, Object> message) { return send(message, false); }
    private boolean send(Map<String, Object> message, boolean initial) {
        WebSocketClient current = socket;
        if (!active() || current == null || !current.isOpen()) return false;
        org.java_websocket.WebSocketImpl connection = (org.java_websocket.WebSocketImpl) current.getConnection();
        long queued = queuedSignalBytes(current);
        int queuedMessages = connection.outQueue.size();
        if (queuedMessages > 128 || queued > 1024 * 1024) {
            terminateBackpressure("outbound_queue", inboundPending.get(), queuedMessages, queued); return false;
        }
        byte[] encoded = FalSignalCodec.encode(message);
        if (initial) diagnostics.initialPayloadBytes(encoded.length);
        current.send(encoded);
        recordSignalQueue(current);
        return true;
    }
    private void enqueue(Map<String, Object> message) {
        recordSignalReceived(FalSignalPolicy.type(message));
        // SDP processing can wait on an asynchronous native callback. A terminal
        // server control needs no SDP state and must be consumed before close.
        String terminalCode = FalSignalPolicy.terminalCode(message);
        if (terminalCode != null) {
            String type = FalSignalPolicy.type(message);
            diagnostics.serverError(message, type.equals("x-fal-error") ? "platform"
                    : "error".equals(message.get("status")) ? "status" : "model");
            terminate(terminalCode); return;
        }
        if (messages.size() >= 32) { terminateBackpressure("decoded_messages", inboundPending.get(), -1, -1); return; }
        messages.add(message); decodedMessageCount = messages.size(); pump();
    }
    private void recordSignalReceived(String type) {
        // Sample time under the same lock as the counters: reader/actor interleaving cannot lose counts.
        synchronized (diagnostics) { diagnostics.signalReceived(type, rtcElapsedMs()); }
    }
    private void pump() {
        if (!active() || processing || messages.isEmpty()) return;
        processing = true;
        Map<String, Object> message = messages.remove(); decodedMessageCount = messages.size();
        processingType = CallTryOnRtcDiagnostics.safeProcessingType(FalSignalPolicy.type(message));
        handle(message, () -> { processing = false; processingType = "none"; pump(); });
    }
    private void handle(Map<String, Object> message, Runnable done) {
        String type = FalSignalPolicy.type(message);
        if (type.equals("prompt_ack") || type.equals("set_image_ack")) {
            if (!firstFrame.get()) diagnostics.initialAcknowledgement(type, message.get("success"));
            acknowledgeUpdate(message); done.run(); return;
        }
        if (type.equals("x-fal-error") && "TIMEOUT".equals(message.get("error"))) { diagnostics.idleTimeout(); done.run(); return; }
        if ("error".equals(message.get("status")) || type.equals("x-fal-error")) {
            diagnostics.serverError(message, type.equals("x-fal-error") ? "platform" : "status");
            terminate("fal_endpoint_error"); return;
        }
        switch (type) {
            case "ready": case "iceservers": {
                Object servers = FalSignalPolicy.initialServers(message);
                if (peer != null) { done.run(); break; }
                if (servers != null) createPeer(servers, done);
                else {
                    if (readyGrace == null) {
                        readyGrace = () -> { readyGrace = null;
                            if (active() && peer == null && !processing) {
                                try {
                                    processing = true; createPeer(List.of(), () -> { processing = false; pump(); });
                                } catch (RuntimeException | LinkageError failure) { diagnostics.exception(failure); terminate("peer_negotiation_failed"); }
                            }
                        };
                        control.postDelayed(readyGrace, 1500);
                    }
                    done.run();
                }
                break;
            }
            case "answer": {
                if (peer == null || remoteDescription || !(message.get("sdp") instanceof String)) { done.run(); break; }
                String sdp = FalSignalPolicy.boundedText(message.get("sdp"), 256 * 1024, false);
                peer.setRemoteDescription(new SdpCallback() {
                    @Override public void onSetSuccess() { post(() -> {
                        remoteDescription = true;
                        for (IceCandidate candidate : candidates) peer.addIceCandidate(candidate);
                        candidates.clear(); state("answer_received"); done.run();
                    }); }
                }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                break;
            }
            case "icecandidate": {
                if (message.get("candidate") == null) { done.run(); break; }
                Map<String, Object> data = FalSignalPolicy.candidate(message.get("candidate"));
                IceCandidate candidate = new IceCandidate((String) data.get("sdpMid"),
                        ((Number) data.get("sdpMLineIndex")).intValue(), (String) data.get("candidate"));
                if (!remoteDescription || peer == null) {
                    if (candidates.size() >= 128) { terminate("too_many_ice_candidates"); return; }
                    candidates.add(candidate);
                } else peer.addIceCandidate(candidate);
                done.run(); break;
            }
            case "ice-restart": {
                if (restarts != 0) { terminate("ice_restart_limit"); return; }
                if (peer == null) { terminate("invalid_ice_restart"); return; }
                Map<String, Object> turn = asMap(message.get("turn_config"));
                List<String> urls = FalSignalPolicy.urls(turn.get("server_url"));
                for (String url : urls) if (!url.startsWith("turn:") && !url.startsWith("turns:")) {
                    terminate("invalid_ice_restart"); return;
                }
                String username = FalSignalPolicy.boundedText(turn.get("username"), 4096, false);
                String credential = FalSignalPolicy.boundedText(turn.get("credential"), 4096, false);
                List<PeerConnection.IceServer> servers = List.of(
                        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                        PeerConnection.IceServer.builder(urls).setUsername(username).setPassword(credential).createIceServer());
                restarts++; remoteDescription = false; candidates.clear();
                if (peerFailureGrace != null) { control.removeCallbacks(peerFailureGrace); peerFailureGrace = null; }
                if (!peer.setConfiguration(configuration(servers))) { terminate("invalid_ice_restart"); return; }
                offer(true, done); break;
            }
            case "generation_started": state("generation_started"); done.run(); break;
            case "error": diagnostics.serverError(message, "model"); terminate("model_reported_error"); break;
            default: done.run(); break;
        }
    }
    private static PeerConnection.RTCConfiguration configuration(List<PeerConnection.IceServer> servers) {
        PeerConnection.RTCConfiguration result = new PeerConnection.RTCConfiguration(servers);
        result.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        result.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        return result;
    }
    private List<PeerConnection.IceServer> iceServers(Object value) {
        if (!(value instanceof List<?>) || ((List<?>) value).size() > 16) throw new IllegalArgumentException("invalid_signal");
        List<PeerConnection.IceServer> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            Map<String, Object> server = asMap(item);
            Object rawUrls = server.containsKey("urls") ? server.get("urls") : server.get("url");
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(FalSignalPolicy.urls(rawUrls));
            if (server.get("username") != null) builder.setUsername(FalSignalPolicy.boundedText(server.get("username"), 4096, false));
            if (server.get("credential") != null) builder.setPassword(FalSignalPolicy.boundedText(server.get("credential"), 4096, false));
            result.add(builder.createIceServer());
        }
        return result;
    }
    private void createPeer(Object servers, Runnable done) {
        if (readyGrace != null) { control.removeCallbacks(readyGrace); readyGrace = null; }
        diagnostics.stage("peer_creating");
        if (servers instanceof List<?>) diagnostics.initialIceServers(((List<?>) servers).size());
        notifyDiagnostic();
        peer = factory.createPeerConnection(configuration(iceServers(servers)), new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState value) { post(() -> {
                diagnostics.ice(value.name().toLowerCase(Locale.ROOT)); notifyDiagnostic();
            }); }
            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState value) { post(() -> {
                diagnostics.gathering(value.name().toLowerCase(Locale.ROOT)); notifyDiagnostic();
            }); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(DataChannel channel) { post(() -> channel.close()); }
            @Override public void onRenegotiationNeeded() {}
            @Override public void onIceCandidate(IceCandidate candidate) { post(() -> send(FalSignalPolicy.map("type", "icecandidate",
                    "candidate", FalSignalPolicy.map("candidate", candidate.sdp, "sdpMid", candidate.sdpMid,
                            "sdpMLineIndex", candidate.sdpMLineIndex)))); }
            @Override public void onAddStream(MediaStream stream) { post(() -> {
                if (!stream.videoTracks.isEmpty()) attach(stream.videoTracks.get(0));
            }); }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) { post(() -> attach(receiver.track())); }
            @Override public void onTrack(RtpTransceiver transceiver) { post(() -> attach(transceiver.getReceiver().track())); }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState value) { post(() -> {
                diagnostics.connection(value.name().toLowerCase(Locale.ROOT)); notifyDiagnostic();
                if (value == PeerConnection.PeerConnectionState.CONNECTED) {
                    if (peerFailureGrace != null) control.removeCallbacks(peerFailureGrace);
                    peerFailureGrace = null; state("media_connected");
                } else if (value == PeerConnection.PeerConnectionState.CLOSED
                        || value == PeerConnection.PeerConnectionState.FAILED && restarts > 0) terminate("peer_failed");
                else if (value == PeerConnection.PeerConnectionState.FAILED && peerFailureGrace == null) {
                    peerFailureGrace = () -> terminate("peer_failed");
                    control.postDelayed(peerFailureGrace, 5000);
                }
            }); }
        });
        if (peer == null) { terminate("peer_creation_failed"); return; }
        peer.setAudioRecording(false); peer.setAudioPlayout(false);
        peer.addTrack(localTrack, List.of("taku-vton-stream"));
        offer(false, done);
    }
    private void attach(MediaStreamTrack track) {
        if (!(track instanceof VideoTrack) || remoteTrack != null) return;
        remoteTrack = (VideoTrack) track;
        remoteTrack.addSink(remoteSink);
        diagnostics.remoteTrack();
        state("remote_track");
    }
    private void offer(boolean restart, Runnable done) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        if (restart) constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        peer.createOffer(new SdpCallback() {
            @Override public void onCreateSuccess(SessionDescription description) { post(() -> {
                peer.setLocalDescription(new SdpCallback() {
                    @Override public void onSetSuccess() { post(() -> {
                        send(FalSignalPolicy.map("type", "offer", "sdp", description.description));
                        state(restart ? "ice_restart_offer_sent" : "offer_sent"); done.run();
                    }); }
                }, description);
            }); }
        }, constraints);
    }
    private class SdpCallback implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { if (active()) { diagnostics.protocolFailure(); terminate("peer_negotiation_failed"); } }
        @Override public void onSetFailure(String error) { if (active()) { diagnostics.protocolFailure(); terminate("peer_negotiation_failed"); } }
    }

    private void sendJpeg(byte[] jpeg, int width, int height, long copiedNs) {
        // Recheck before decode/allocation: an accepted worker may begin after stop.
        // In particular JavaI420Buffer.allocate must never race native library loading.
        if (!active() || sourceState.get() != SOURCE_READY) return;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth != width || bounds.outHeight != height || !"image/jpeg".equals(bounds.outMimeType))
            throw new IllegalArgumentException("invalid_source_frame");
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) throw new IllegalArgumentException("invalid_source_frame");
        JavaI420Buffer buffer = null;
        try {
            int[] target = FalSignalPolicy.contain(width, height, 0);
            if (target[0] != width || target[1] != height) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, target[0], target[1], true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
                width = target[0]; height = target[1];
            }
            int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            buffer = JavaI420Buffer.allocate(width, height);
            RtcPixelConversion.argbToI420(pixels, width, height, buffer.getDataY(), buffer.getStrideY(),
                    buffer.getDataU(), buffer.getStrideU(), buffer.getDataV(), buffer.getStrideV());
            synchronized (sourceLock) {
                if (!active() || sourceState.get() != SOURCE_READY || videoSource == null) return;
                lastSourceNs = Math.max(lastSourceNs + 1, copiedNs > 0 ? copiedNs : System.nanoTime());
                VideoFrame frame = new VideoFrame(buffer, 0, lastSourceNs);
                buffer = null; // VideoFrame owns its initial reference.
                try { videoSource.getCapturerObserver().onFrameCaptured(frame); diagnostics.inputFrame(width, height, lastSourceNs); }
                finally { frame.release(); }
            }
        } finally { if (buffer != null) buffer.release(); bitmap.recycle(); }
    }
    private void receiveFrame(VideoFrame frame) {
        if (!active()) return;
        diagnostics.remoteFrame();
        if (!outputBusy.compareAndSet(false, true)) return;
        frame.retain();
        try {
            output.execute(() -> {
                try {
                    if (!active()) return;
                    byte[] jpeg;
                    int width, height;
                    Bitmap bitmap = remoteBitmap(frame);
                    try {
                        while (true) {
                            jpeg = null;
                            for (int quality : new int[]{85, 70, 55, 40}) {
                                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                                    throw new IllegalArgumentException("return_frame_encoding_failed");
                                if (bytes.size() <= FalSignalPolicy.MAX_JPEG) { jpeg = bytes.toByteArray(); break; }
                            }
                            if (jpeg != null) break;
                            int w = bitmap.getWidth() * 3 / 4, h = bitmap.getHeight() * 3 / 4;
                            if (w < 64 || h < 64) throw new IllegalArgumentException("return_frame_oversized");
                            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
                            if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
                        }
                        width = bitmap.getWidth(); height = bitmap.getHeight();
                    } finally { bitmap.recycle(); }
                    if (active()) {
                        diagnostics.jpegFrame();
                        if (firstFrame.compareAndSet(false, true)) state("live");
                        if (active()) listener.onRemoteFrame(jpeg, width, height);
                    }
                } catch (RuntimeException | LinkageError | OutOfMemoryError failure) { diagnostics.exception(failure); terminate("return_frame_failed"); }
                finally { frame.release(); outputBusy.set(false); }
            });
        } catch (RejectedExecutionException failure) { frame.release(); outputBusy.set(false); }
    }
    private Bitmap remoteBitmap(VideoFrame frame) {
        VideoFrame.Buffer original = frame.getBuffer();
        int rotation = frame.getRotation();
        int[] dimensions = FalSignalPolicy.contain(original.getWidth(), original.getHeight(), rotation);
        VideoFrame.Buffer scaled = original.cropAndScale(0, 0, original.getWidth(), original.getHeight(), dimensions[0], dimensions[1]);
        VideoFrame.I420Buffer buffer = null;
        Bitmap bitmap = null;
        try {
            buffer = scaled.toI420();
            int width = buffer.getWidth(), height = buffer.getHeight();
            int[] argb = RtcPixelConversion.i420ToArgb(width, height, buffer.getDataY(), buffer.getStrideY(),
                    buffer.getDataU(), buffer.getStrideU(), buffer.getDataV(), buffer.getStrideV());
            bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            if (rotation != 0) {
                Matrix matrix = new Matrix(); matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated; }
            }
            Bitmap result = bitmap; bitmap = null; return result;
        } finally { if (bitmap != null) bitmap.recycle(); if (buffer != null) buffer.release(); scaled.release(); }
    }
    private record SignalBackpressure(String source, String processingType, int inbound, int decoded, int outbound, long bytes) {}
    private void terminateBackpressure(String source, int inbound, int outbound, long bytes) {
        if (outbound < 0) {
            WebSocketClient current = socket;
            if (current != null) try {
                org.java_websocket.WebSocketImpl connection = (org.java_websocket.WebSocketImpl) current.getConnection();
                outbound = connection.outQueue.size(); bytes = queuedSignalBytes(current);
            } catch (RuntimeException | LinkageError ignored) { outbound = -1; bytes = -1; }
        }
        terminate("signal_backpressure", new SignalBackpressure(source,
                "inbound_pending".equals(source) ? "unknown" : processingType, inbound, decodedMessageCount, outbound, bytes));
    }
    private void terminate(String code) { terminate(code, null); }
    private void terminate(String code, SignalBackpressure pressure) {
        if (!stopped.compareAndSet(false, true)) return;
        if (pressure != null) diagnostics.signalBackpressure(pressure.source(), pressure.processingType(), pressure.inbound(),
                pressure.decoded(), pressure.outbound(), pressure.bytes());
        diagnostics.finish(code);
        notifyDiagnostic(); // Preserve the originating evidence before onFailure triggers service cleanup.
        synchronized (updates) {
            updates.close(); pendingUpdate = null; activeReference = null;
        }
        sourceState.set(SOURCE_CLOSED);
        watchdog.shutdownNow(); input.shutdown(); output.shutdown();
        final FalSignalingSocketFactory transport;
        final WebSocketClient current;
        synchronized (socketLock) {
            transport = signalingTransport;
            if (transport != null) transport.cancel();
            current = socket;
        }
        if (code != null) try { listener.onFailure(code); } catch (RuntimeException ignored) {}
        control.removeCallbacksAndMessages(null);
        boolean cleanupQueued=control.post(() -> {
            boolean confirmed=true;
            // Conscrypt TLS close-notify can write even after raw-socket cancellation.
            // Keep both close paths off the UI thread and before the cleanup receipt.
            try { if (transport != null) confirmed = transport.completeClose(); }
            catch (RuntimeException | LinkageError failed) { confirmed=false; }
            try { if (current != null) current.closeConnection(1000, "tryon_session_complete"); }
            catch (RuntimeException | LinkageError failed) { confirmed=false; }
            try {
                if (remoteTrack != null) remoteTrack.removeSink(remoteSink);
                if (peer != null) { peer.close(); peer.dispose(); peer = null; }
                synchronized (sourceLock) {
                    if (localTrack != null) { localTrack.dispose(); localTrack = null; }
                    if (videoSource != null) { videoSource.getCapturerObserver().onCapturerStopped(); videoSource.dispose(); videoSource = null; }
                }
                if (factory != null) { factory.dispose(); factory = null; }
            } catch (RuntimeException | LinkageError ignored) { confirmed=false; }
            finally {
                messages.clear(); decodedMessageCount = 0; processingType = "none"; candidates.clear(); socket = null; signalingTransport = null;
                try { listener.onState("closed"); } catch (RuntimeException ignored) {}
                try { listener.onCleanup(confirmed); } catch (RuntimeException ignored) {}
                controlThread.quitSafely();
            }
        });
        if(!cleanupQueued)try{listener.onCleanup(false);}catch(RuntimeException ignored){}
    }
}
