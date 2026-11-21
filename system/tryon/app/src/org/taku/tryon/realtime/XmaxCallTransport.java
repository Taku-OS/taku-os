// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import io.agora.base.JavaI420Buffer;
import io.agora.base.VideoFrame;
import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.DataStreamConfig;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.UserInfo;
import io.agora.rtc2.video.IVideoFrameObserver;
import io.agora.rtc2.video.VideoEncoderConfiguration;

/** Native Xmax X2.1 external video only. No camera, microphone, local relay, SDK logging or persisted pixels. */
final class XmaxCallTransport implements RealtimeCallTransport {
    private static final XmaxEngineLease ENGINE = new XmaxEngineLease();
    private final Context context;
    private final CallTryOnRtc.Listener listener;
    private final XmaxSessionClient client;
    private final CallTryOnRtcDiagnostics diagnostics = new CallTryOnRtcDiagnostics();
    private final XmaxStartupDeadlines startupDeadlines = new XmaxStartupDeadlines(XmaxSessionClient.SESSION_MS, XmaxSessionClient.FIRST_FRAME_MS);
    private final AtomicBoolean started = new AtomicBoolean(), stopped = new AtomicBoolean(), inputBusy = new AtomicBoolean(), outputBusy = new AtomicBoolean();
    private final AtomicBoolean firstFrame = new AtomicBoolean();
    private final AtomicBoolean heartbeatBusy = new AtomicBoolean();
    private final AtomicLong lastStatsPublishNs = new AtomicLong();
    private volatile long diagnosticStartedNs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "xmax-native-owner"));
    private final ExecutorService output = Executors.newSingleThreadExecutor(r -> new Thread(r, "xmax-native-return"));
    private final ExecutorService heartbeat = Executors.newSingleThreadExecutor(r -> new Thread(r, "xmax-native-heartbeat"));
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "xmax-native-deadline"));
    // Worker owns engine/session/generation state. Callback reads are volatile and never revive stopped instances.
    private volatile RtcEngine engine;
    private volatile XmaxSessionClient.Session session;
    private volatile int botUid;
    private volatile boolean botMapped, joined, generationSent;
    private boolean generationReserved;
    private String prompt, reference, task;
    private int dataStream = -1, sourceWidth, sourceHeight;
    private long lastSourceNs;

    XmaxCallTransport(Context context, String environment, CallTryOnRtc.Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("invalid_request");
        this.context = context.getApplicationContext(); this.listener = listener; client = new XmaxSessionClient(environment);
    }
    @Override public void start(String credential, String prompt, String referenceDataUriOrNull) {
        if (stopped.get() || !started.compareAndSet(false, true)) return;
        try { FalSignalPolicy.initial(prompt, referenceDataUriOrNull); }
        catch (RuntimeException invalid) { fail("invalid_request"); return; }
        this.prompt = prompt; diagnosticStartedNs = System.nanoTime(); startupDeadlines.startSession(diagnosticStartedNs);
        diagnostics.begin(); diagnostics.initialReferenceProvided(referenceDataUriOrNull != null); publish();
        try {
            deadline.schedule(() -> { if (startupDeadlines.sessionExpired(System.nanoTime())) fail("session_limit"); },
                    startupDeadlines.sessionDelayMs(), TimeUnit.MILLISECONDS);
            enqueue(() -> {
                try {
                    session = client.open(credential); if (stopped.get()) return;
                    reference = client.uploadReference(referenceDataUriOrNull); if (stopped.get()) return;
                    task = UUID.randomUUID().toString(); createEngine();
                } catch (XmaxSessionClient.Failure failure) { if (!stopped.get()) providerFailure(failure); }
                catch (Throwable ignored) { fail("peer_creation_failed"); }
            });
        } catch (RejectedExecutionException ignored) { if (!stopped.get()) fail("rtc_internal_error"); }
    }
    private void createEngine() throws Exception {
        if (!ENGINE.acquire(this, stopped::get, 5000)) { if (!stopped.get()) throw new IllegalStateException("rtc_busy"); return; }
        {
            if (stopped.get()) return;
            RtcEngineConfig config = new RtcEngineConfig(); config.mContext = context; config.mAppId = XmaxSessionClient.APP_ID;
            config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING; config.mEventHandler = events;
            config.mLogConfig = new RtcEngineConfig.LogConfig(); config.mLogConfig.level = Constants.LogLevel.getValue(Constants.LogLevel.LOG_LEVEL_NONE);
            engine = RtcEngine.create(config);
        }
        if (stopped.get()) return;
        check(engine.enableVideo()); check(engine.enableLocalVideo(false)); check(engine.disableAudio()); check(engine.enableLocalAudio(false));
        check(engine.setExternalVideoSource(true, false, Constants.ExternalVideoSourceType.VIDEO_FRAME));
        check(engine.registerVideoFrameObserver(observer)); diagnostics.stage("peer_creating"); publish(); state("connecting");
        ChannelMediaOptions options = new ChannelMediaOptions();
        options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING; options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        options.publishCameraTrack = false; options.publishMicrophoneTrack = false; options.publishCustomAudioTrack = false;
        options.publishCustomVideoTrack = true; options.customVideoTrackId = 0; options.autoSubscribeAudio = false; options.autoSubscribeVideo = false;
        check(engine.joinChannelWithUserAccount(session.token, session.room, session.user, options));
    }
    private final IRtcEngineEventHandler events = new IRtcEngineEventHandler() {
        @Override public void onJoinChannelSuccess(String channel, int uid, int elapsed) { enqueue(() -> {
            try {
                if (!session.room.equals(channel)) return;
                DataStreamConfig config = new DataStreamConfig(); config.ordered = true; config.syncWithAudio = false;
                dataStream = engine.createDataStream(config); check(dataStream); joined = true;
                diagnostics.connection("connected"); diagnostics.stage("media_connected"); publish(); maybeGenerate();
                deadline.scheduleWithFixedDelay(() -> {
                    enqueue(() -> send(XmaxSignalPolicy.event("heartbeat", session.user, null), null));
                    if (stopped.get() || !heartbeatBusy.compareAndSet(false, true)) return;
                    try { heartbeat.execute(() -> { try { if (!stopped.get()) client.heartbeat(); }
                        catch (XmaxSessionClient.Failure failure) { if (!stopped.get()) providerFailure(failure); }
                        catch (Exception ignored) { if (!stopped.get()) fail("model_reported_error"); }
                        finally { heartbeatBusy.set(false); } }); }
                    catch (RejectedExecutionException ignored) { heartbeatBusy.set(false); }
                }, 10, 10, TimeUnit.SECONDS);
            } catch (Exception ignored) { fail("peer_negotiation_failed"); }
        }); }
        @Override public void onUserInfoUpdated(int uid, UserInfo info) {
            if (!stopped.get() && session != null && session.bot.equals(info.userAccount)) enqueue(() -> {
                botUid = uid; botMapped = true; diagnostics.agoraBotMapped(); checkOrFail(engine.muteRemoteVideoStream(uid, false)); publish();
            });
        }
        @Override public void onUserJoined(int uid, int elapsed) { enqueue(() -> {
            UserInfo info = new UserInfo(); if (engine.getUserInfoByUid(uid, info) == 0) onUserInfoUpdated(uid, info);
        }); }
        @Override public void onUserOffline(int uid, int reason) { if (botMapped && uid == botUid && !stopped.get()) {
            diagnostics.agoraPublication("unpublished", "user_offline"); fail("peer_failed");
        } }
        @Override public void onRemoteVideoStateChanged(int uid, int value, int reason, int elapsed) {
            if (!currentBot(uid)) return;
            diagnostics.agoraRemoteVideoState(value, reason, elapsed); publish();
        }
        @Override public void onVideoSubscribeStateChanged(String channel, int uid, int previous, int value, int elapsed) {
            XmaxSessionClient.Session current = session;
            if (!currentBot(uid) || current == null || !current.room.equals(channel)) return;
            diagnostics.agoraSubscription(previous, value, elapsed); publish();
        }
        @Override public void onFirstRemoteVideoDecoded(int uid, int width, int height, int elapsed) {
            if (!currentBot(uid)) return;
            diagnostics.agoraFirstDecoded(width, height, elapsed); publish();
        }
        @Override public void onFirstRemoteVideoFrame(int uid, int width, int height, int elapsed) {
            if (!currentBot(uid)) return;
            diagnostics.agoraFirstRendered(elapsed); publish();
        }
        @Override public void onLocalVideoStats(Constants.VideoSourceType source, LocalVideoStats stats) {
            if (stopped.get() || !joined || session == null || stats == null || source != Constants.VideoSourceType.VIDEO_SOURCE_CUSTOM) return;
            diagnostics.agoraUplinkStats(diagnosticElapsedMs(), stats.sentBitrate, stats.sentFrameRate,
                    stats.encoderOutputFrameRate, stats.txPacketLossRate); publishStatsIfDue();
        }
        @Override public void onRemoteVideoStats(RemoteVideoStats stats) {
            if (stats == null || !currentBot(stats.uid)) return;
            diagnostics.agoraDownlinkStats(diagnosticElapsedMs(), stats.receivedBitrate, stats.decoderOutputFrameRate,
                    stats.rendererOutputFrameRate, stats.packetLossRate); publishStatsIfDue();
        }
        @Override public void onError(int error) { if (error != 0) fail("peer_failed"); }
        @Override public void onConnectionLost() { fail("peer_failed"); }
        @Override public void onConnectionStateChanged(int connectionState, int reason) {
            if (connectionState == Constants.CONNECTION_STATE_FAILED) fail("peer_failed");
        }
        @Override public void onRequestToken() { fail("cloud_auth_failed"); }
        @Override public void onStreamMessageError(int uid, int streamId, int error, int missed, int cached) { fail("model_reported_error"); }
        @Override public void onStreamMessage(int uid, int streamId, byte[] data) {
            if (stopped.get() || !botMapped || uid != botUid || data == null || data.length > 1024) return;
            diagnostics.agoraStreamMessage();
            // Android SDK 1.1.0 defines no generation ACK. Explicit terminal signals are failures, never success evidence.
            try {
                org.json.JSONObject message = new org.json.JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                String event = message.optString("event"); String status = message.optString("status");
                if ("error".equals(event) || Boolean.FALSE.equals(message.opt("success")) ||
                        "FAILED".equals(status) || "ERROR".equals(status) || "REJECTED".equals(status)) {
                    diagnostics.agoraTerminalMessage(); fail("model_reported_error");
                }
            } catch (Exception ignored) { }
        }
    };
    /** Callbacks belong to this engine/room; only its mapped result publisher contributes media metadata. */
    private boolean currentBot(int uid) { return !stopped.get() && joined && session != null && botMapped && uid == botUid; }
    private long diagnosticElapsedMs() { return diagnosticStartedNs == 0 ? -1 : Math.max(0, (System.nanoTime()-diagnosticStartedNs)/1_000_000L); }
    /** Agora periodic statistics update memory on arrival but emit at most one snapshot per checkpoint. */
    private void publishStatsIfDue() {
        long now = System.nanoTime(), previous = lastStatsPublishNs.get();
        if (!stopped.get() && (previous==0 || now-previous >= CallTryOnRtcDiagnostics.CHECKPOINT_MS*1_000_000L)
                && lastStatsPublishNs.compareAndSet(previous, now)) publish();
    }
    private void maybeGenerate() {
        if (stopped.get() || !joined || sourceWidth == 0 || task == null || generationReserved) return;
        // Reserve before queued packets; callbacks cannot duplicate a paid generation start.
        generationReserved = true;
        send(XmaxSignalPolicy.start(session.user, task, prompt, reference, sourceWidth, sourceHeight), () -> {
            generationSent = true; diagnostics.initialSendResult(true); diagnostics.stage("generation_started"); publish();
        }, true);
    }
    private void send(String message, Runnable completion) { send(message, completion, false); }
    private void send(String message, Runnable completion, boolean generationStart) {
        List<byte[]> packets = XmaxSignalPolicy.packets(message);
        for (int index = 0; index < packets.size(); index++) {
            final int position = index;
            try { deadline.schedule(() -> enqueue(() -> {
                if (generationStart && position == 0) {
                    // Arm only when the owner actually submits the first start packet, after queueing/preparation.
                    if (!startupDeadlines.startGeneration(System.nanoTime())) { fail("session_limit"); return; }
                    deadline.schedule(() -> { if (startupDeadlines.claimFirstFrameTimeout(System.nanoTime())) fail("first_frame_timeout"); },
                            startupDeadlines.firstFrameDelayMs(), TimeUnit.MILLISECONDS);
                }
                if (engine.sendStreamMessage(dataStream, packets.get(position)) < 0) { fail("signal_backpressure"); return; }
                if (position == packets.size() - 1 && completion != null) completion.run();
            }), index * 50L, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException ignored) { return; }
        }
    }
    @Override public void sendFrame(byte[] jpeg, int width, int height, long copiedNs) {
        if (!started.get() || stopped.get() || jpeg == null || jpeg.length < 4 || jpeg.length > FalSignalPolicy.MAX_JPEG ||
                width < 1 || height < 1 || width > FalSignalPolicy.MAX_SOURCE_EDGE || height > FalSignalPolicy.MAX_SOURCE_EDGE ||
                (long) width * height > FalSignalPolicy.MAX_SOURCE_PIXELS || copiedNs < 0 || !inputBusy.compareAndSet(false, true)) return;
        byte[] owned = jpeg.clone();
        long queuedAt = System.nanoTime();
        try { worker.execute(() -> {
            Bitmap bitmap = null, scaled = null; JavaI420Buffer buffer = null;
            try {
                // copiedNs is RTC metadata; HAL and local-camera sources use different clocks.
                // Source freshness is validated by the service; this gate bounds only our own queue delay.
                if (stopped.get() || engine == null || !joined || copiedNs <= lastSourceNs || !XmaxSignalPolicy.freshSource(queuedAt, System.nanoTime())) return;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(owned, 0, owned.length, bounds);
                if (bounds.outWidth != width || bounds.outHeight != height) throw new IllegalArgumentException("input_frame_failed");
                bitmap = BitmapFactory.decodeByteArray(owned, 0, owned.length); if (bitmap == null) throw new IllegalArgumentException("input_frame_failed");
                int[] size = FalSignalPolicy.contain(width, height, 0); size[0] = Math.max(4, size[0] / 4 * 4); size[1] = Math.max(4, size[1] / 4 * 4);
                if (sourceWidth == 0) {
                    VideoEncoderConfiguration config = new VideoEncoderConfiguration();
                    config.dimensions = new VideoEncoderConfiguration.VideoDimensions(size[0], size[1]);
                    config.frameRate = 15; config.bitrate = 1500; config.minBitrate = 500;
                    config.orientationMode = size[0] > size[1] ? VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE :
                            VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT;
                    config.mirrorMode = VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED;
                    check(engine.setVideoEncoderConfiguration(config));
                }
                scaled = Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
                int[] argb = new int[size[0] * size[1]]; scaled.getPixels(argb, 0, size[0], 0, 0, size[0], size[1]);
                buffer = JavaI420Buffer.allocate(size[0], size[1]);
                RtcPixelConversion.argbToI420(argb, size[0], size[1], buffer.getDataY(), buffer.getStrideY(), buffer.getDataU(), buffer.getStrideU(), buffer.getDataV(), buffer.getStrideV());
                VideoFrame frame = new VideoFrame(buffer, 0, copiedNs); buffer = null;
                try { if (stopped.get()) return; if (!engine.pushExternalVideoFrame(frame)) throw new IllegalStateException("input_frame_failed"); }
                finally { frame.release(); }
                lastSourceNs = copiedNs; sourceWidth = size[0]; sourceHeight = size[1];
                diagnostics.inputFrame(sourceWidth, sourceHeight, copiedNs); maybeGenerate();
            } catch (Throwable ignored) { fail("input_frame_failed"); }
            finally { if (buffer != null) buffer.release(); if (scaled != null && scaled != bitmap) scaled.recycle(); if (bitmap != null) bitmap.recycle(); inputBusy.set(false); }
        }); } catch (RejectedExecutionException ignored) { inputBusy.set(false); }
    }
    private final IVideoFrameObserver observer = new IVideoFrameObserver() {
        @Override public boolean onCaptureVideoFrame(int source, VideoFrame frame) { return true; }
        @Override public boolean onPreEncodeVideoFrame(int source, VideoFrame frame) { return true; }
        @Override public boolean onMediaPlayerVideoFrame(VideoFrame frame, int player) { return true; }
        @Override public int getVideoFrameProcessMode() { return PROCESS_MODE_READ_ONLY; }
        @Override public int getVideoFormatPreference() { return VIDEO_PIXEL_I420; }
        @Override public boolean getRotationApplied() { return false; }
        @Override public boolean getMirrorApplied() { return false; }
        @Override public int getObservedFramePosition() { return POSITION_PRE_RENDERER; }
        @Override public boolean onRenderVideoFrame(String channel, int uid, VideoFrame frame) {
            // Diagnostic-only raw entry buckets preserve the original reject conditions and one-conversion ownership.
            if (stopped.get()) { diagnostics.agoraObserverFrame("stopped"); return true; }
            XmaxSessionClient.Session current = session;
            if (current == null || !current.room.equals(channel)) { diagnostics.agoraObserverFrame("wrong_room"); return true; }
            if (!botMapped) { diagnostics.agoraObserverFrame("unmapped_bot"); return true; }
            if (uid != botUid) { diagnostics.agoraObserverFrame("wrong_bot"); return true; }
            if (!generationSent) { diagnostics.agoraObserverFrame("before_start"); return true; }
            if (!outputBusy.compareAndSet(false, true)) { diagnostics.agoraObserverFrame("busy"); return true; }
            diagnostics.agoraObserverFrame("accepted");
            frame.retain();
            try { output.execute(() -> { try { if (!stopped.get()) receive(frame); } finally { frame.release(); outputBusy.set(false); } }); }
            catch (RejectedExecutionException ignored) { frame.release(); outputBusy.set(false); }
            return true;
        }
    };
    private void receive(VideoFrame frame) {
        VideoFrame.I420Buffer i420 = null; Bitmap bitmap = null, rotated = null;
        try {
            int width = frame.getBuffer().getWidth(), height = frame.getBuffer().getHeight();
            if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > 2_100_000) throw new IllegalArgumentException("return_frame_failed");
            i420 = frame.getBuffer().toI420();
            int[] argb = RtcPixelConversion.i420ToArgb(width, height, i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(), i420.getStrideV());
            bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888); rotated = bitmap;
            if (frame.getRotation() != 0) { Matrix matrix = new Matrix(); matrix.postRotate(frame.getRotation()); rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true); }
            int[] size = FalSignalPolicy.contain(rotated.getWidth(), rotated.getHeight(), 0);
            Bitmap scaled = Bitmap.createScaledBitmap(rotated, size[0], size[1], true);
            byte[] jpeg = null;
            try { for (int quality : new int[]{82, 68, 50, 35}) { ByteArrayOutputStream out = new ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, out); if (out.size() <= FalSignalPolicy.MAX_JPEG) { jpeg = out.toByteArray(); break; } } }
            finally { if (scaled != bitmap && scaled != rotated) scaled.recycle(); }
            if (jpeg == null) throw new IllegalArgumentException("return_frame_failed");
            if (stopped.get() || !startupDeadlines.acceptFrame()) return;
            diagnostics.remoteFrame(); diagnostics.jpegFrame();
            if (firstFrame.compareAndSet(false, true)) { diagnostics.stage("video_live"); publish(); state("live"); }
            if (!stopped.get()) listener.onRemoteFrame(jpeg, size[0], size[1]);
        } catch (Throwable ignored) { fail("return_frame_failed"); }
        finally { if (i420 != null) i420.release(); if (rotated != null && rotated != bitmap) rotated.recycle(); if (bitmap != null) bitmap.recycle(); }
    }
    @Override public boolean update(String prompt, String reference, CallTryOnRtc.UpdateListener completion) { return false; }
    @Override public String updateAvailability() { return "unsupported"; }
    @Override public Map<String, Object> diagnosticSnapshot() { return diagnostics.snapshot(); }
    @Override public void stop() { finish(null); }
    private void providerFailure(XmaxSessionClient.Failure failure) {
        diagnostics.providerFailure(failure.providerStep, failure.providerFailureKind, failure.providerHttpStatus);
        fail(failure.code);
    }
    private void fail(String code) { finish(code); }
    private void finish(String code) {
        if (!stopped.compareAndSet(false, true)) return;
        startupDeadlines.stop();
        ENGINE.retire(this); client.cancel(); deadline.shutdownNow(); output.shutdown(); heartbeat.shutdown();
        diagnostics.finish(code); publish();
        if (code != null) { try { listener.onFailure(code); } catch (Throwable ignored) {} }
        try { worker.execute(() -> {
            try {
                if (ENGINE.owns(this)) {
                    try { if (engine != null) { engine.registerVideoFrameObserver(null); engine.leaveChannel(); RtcEngine.destroy(); } }
                    finally { engine = null; ENGINE.release(this); }
                }
            } catch (Throwable ignored) { }
            finally {
                boolean confirmed = client.close();
                try { listener.onCleanup(confirmed); } catch (Throwable ignored) { }
                session = null; prompt = reference = task = null; worker.shutdown();
            }
        }); } catch (RejectedExecutionException ignored) { }
    }
    private void enqueue(Runnable action) {
        if (stopped.get()) return;
        try { worker.execute(() -> { if (!stopped.get()) try { action.run(); } catch (Throwable ignored) { fail("rtc_internal_error"); } }); }
        catch (RejectedExecutionException ignored) { }
    }
    private static void check(int result) { if (result < 0) throw new IllegalStateException("rtc_internal_error"); }
    private void checkOrFail(int result) { if (result < 0) fail("peer_failed"); }
    private void state(String state) { if (!stopped.get()) try { listener.onState(state); } catch (Throwable ignored) { fail("listener_failed"); } }
    private void publish() { try { listener.onDiagnostic(diagnostics.snapshot()); } catch (Throwable ignored) { } }
}
