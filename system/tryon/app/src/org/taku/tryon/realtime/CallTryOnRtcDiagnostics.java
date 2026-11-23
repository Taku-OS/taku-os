// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Flat, bounded transport evidence. Upstream strings and session material never enter a snapshot. */
final class CallTryOnRtcDiagnostics {
    static final long CHECKPOINT_MS = 2000;
    private static final long MAX_COUNTER = 9007199254740991L;
    private static final long MAX_ELAPSED_MS = 60000;
    private static final Set<String> BACKPRESSURE_SOURCES = Set.of("inbound_pending", "outbound_queue", "decoded_messages");
    private static final Map<String, String> SIGNAL_COUNTS = Map.ofEntries(
            Map.entry("ready", "signalReadyCount"), Map.entry("iceservers", "signalIceServersCount"),
            Map.entry("answer", "signalAnswerCount"), Map.entry("icecandidate", "signalIceCandidateCount"),
            Map.entry("ice-restart", "signalIceRestartCount"), Map.entry("prompt_ack", "signalPromptAckCount"),
            Map.entry("set_image_ack", "signalSetImageAckCount"), Map.entry("generation_started", "signalGenerationStartedCount"),
            Map.entry("error", "signalErrorCount"), Map.entry("x-fal-error", "signalFalErrorCount"),
            Map.entry("x-fal-message", "signalFalMessageCount"));
    private static final Set<String> STAGES = Set.of("opening", "signaling_connected", "peer_creating",
            "offer_sent", "answer_received", "media_connected", "generation_started", "video_live");
    private static final Set<String> CONNECTION = Set.of("new", "connecting", "connected", "disconnected", "failed", "closed");
    private static final Set<String> ICE = Set.of("new", "checking", "connected", "completed", "disconnected", "failed", "closed");
    private static final Set<String> GATHERING = Set.of("new", "gathering", "complete");
    private static final Set<String> ROUTES = Set.of("direct", "http_connect_proxy", "socks_proxy");
    private static final Set<String> TRANSPORT_STAGES = Set.of("proxy_connecting", "proxy_tunneling",
            "tls_handshaking", "websocket_handshaking", "connected");
    private static final Set<String> PROVIDER_STEPS = Set.of("temporary_key", "session_create", "session_parse",
            "reference_sts", "reference_upload", "heartbeat", "cleanup", "unknown");
    private static final Set<String> PROVIDER_FAILURE_KINDS = Set.of("malformed_json", "missing_data", "response_too_large",
            "business_rejected", "missing_temporary_key", "missing_session_id", "invalid_model_extra",
            "missing_room_id", "missing_rtc_user_id", "missing_room_token", "missing_bot_id", "missing_app_id",
            "app_id_mismatch", "inactive_session", "invalid_sts_bucket", "invalid_sts_region", "invalid_sts_prefix",
            "missing_sts_credentials", "invalid_sts_access_key", "invalid_sts_secret_key", "invalid_sts_token",
            "http_rejected", "network_failure", "invalid_reference", "cancelled", "unknown");
    private static final Set<String> REMOTE_PUBLICATION = Set.of("published", "unpublished");
    private static final Set<String> PUBLICATION_SOURCES = Set.of("user_offline", "video_state");
    private static final Map<String,String> OBSERVER_GATES = Map.of("accepted", "remoteObserverAcceptedFrames",
            "before_start", "remoteObserverBeforeStartDrops", "busy", "remoteObserverBusyDrops", "stopped", "remoteObserverStoppedDrops",
            "wrong_room", "remoteObserverWrongRoomDrops", "unmapped_bot", "remoteObserverUnmappedBotDrops", "wrong_bot", "remoteObserverWrongBotDrops");
    private static final Set<String> FAILURES = Set.of("invalid_request", "session_limit", "lucy_session_busy", "first_frame_timeout",
            "rtc_internal_error", "listener_failed", "input_frame_failed", "return_frame_failed", "invalid_signal",
            "websocket_closed", "websocket_error", "signal_backpressure", "fal_endpoint_error",
            "peer_negotiation_failed", "too_many_ice_candidates", "ice_restart_limit", "invalid_ice_restart",
            "model_reported_error", "peer_failed", "peer_creation_failed", "model_control_rejected", "cloud_auth_failed");
    private static final Set<String> COUNTERS = Set.of("bytesSent", "bytesReceived", "framesSent", "framesReceived",
            "framesEncoded", "framesDecoded", "packetsSent", "packetsReceived", "packetsLost", "framesDropped",
            "remotePacketsLost", "remotePacketsReceived", "remoteRoundTripTimeMeasurements", "remoteReportsReceived",
            "candidatePairBytesSent", "candidatePairBytesReceived");
    private static final Set<String> GAUGES = Set.of("remoteRoundTripTimeMs", "currentRoundTripTimeMs", "availableOutgoingBitrate", "remoteFractionLost");
    private static final Set<String> SERVER_KINDS = Set.of("media_connection_timeout", "media_connection_failed",
            "input_frames_timeout", "invalid_reference", "reference_required", "invalid_prompt", "unsupported_video",
            "auth_failed", "quota_exceeded", "rate_limited", "service_unavailable", "service_internal_error", "content_rejected");
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean terminal, statsPending;
    private long lastStatsRequest = -1, snapshotRevision, firstInputNs = -1, lastInputNs = -1;

    CallTryOnRtcDiagnostics() {
        values.put("diagnosticSchemaVersion", 2L);
        values.put("state", "idle"); values.put("stage", "opening"); values.put("errorCode", "none");
        for (String key : new String[]{"websocketOpened", "remoteTrackReceived", "offerSent", "answerReceived", "generationStarted"})
            values.put(key, false);
        values.put("initialSignalSendAttempted", false); values.put("initialSignalSent", false);
        values.put("initialPromptAck", "not_received"); values.put("initialImageAck", "not_received");
        for (String key : new String[]{"inputFrames", "remoteFrames", "jpegFrames", "platformIdleTimeouts"}) values.put(key, 0L);
        values.put("signalMessagesReceived", 0L); values.put("signalUnknownCount", 0L);
        for (String counter : SIGNAL_COUNTS.values()) values.put(counter, 0L);
        values.put("statsReadState", "not_started");
    }

    synchronized void begin() { if (!terminal) values.put("state", "opening"); }
    synchronized void stage(String stage) {
        if (terminal || stage == null || !STAGES.contains(stage)) return;
        values.put("stage", stage); values.put("state", "video_live".equals(stage) ? "live" : "connecting");
        if ("offer_sent".equals(stage)) values.put("offerSent", true);
        if ("answer_received".equals(stage)) values.put("answerReceived", true);
        if ("generation_started".equals(stage)) values.put("generationStarted", true);
    }
    synchronized void handshake(int status) {
        if (!terminal && status >= 100 && status <= 599) values.put("handshakeHttpStatus", status);
    }
    synchronized void route(String route) { enumeration("connectionRoute", route, ROUTES); }
    synchronized void transportStage(String stage) { enumeration("transportStage", stage, TRANSPORT_STAGES); }
    synchronized void proxyConnectStatus(int status) {
        if (!terminal && status >= 100 && status <= 599) values.put("proxyConnectStatus", status);
    }
    /** The provider adapter supplies fixed preparation metadata, never responses or endpoint values. */
    synchronized void providerFailure(String step, String kind, int httpStatus) {
        if (terminal || values.containsKey("providerStep")) return;
        if (step != null && PROVIDER_STEPS.contains(step)) values.put("providerStep", step);
        if (kind != null && PROVIDER_FAILURE_KINDS.contains(kind)) values.put("providerFailureKind", kind);
        if (httpStatus >= 100 && httpStatus <= 599) values.put("providerHttpStatus", httpStatus);
    }
    synchronized void websocketOpened() { if (!terminal) values.put("websocketOpened", true); }
    /** Parsed signal metadata only. Unknown type contents never enter a snapshot. */
    synchronized void signalReceived(String type, long elapsedMs) {
        if (terminal || !elapsed(elapsedMs) || values.get("lastSignalAtMs") instanceof Number
                && elapsedMs < ((Number) values.get("lastSignalAtMs")).longValue()) return;
        String counter = type == null || type.length() > 64 ? null : SIGNAL_COUNTS.get(type);
        increment("signalMessagesReceived");
        increment(counter == null ? "signalUnknownCount" : counter);
        values.put("lastSignalType", counter == null ? "unknown" : type);
        values.put("lastSignalAtMs", elapsedMs);
    }
    synchronized void initialPayloadBytes(int bytes) {
        if (!terminal && bytes >= 0 && !values.containsKey("initialPayloadBytes")) values.put("initialPayloadBytes", bytes);
    }
    /** Initial request measurements only: absent means unknown; zero dimensions/bytes mean no reference. */
    synchronized void initialInputMetadata(int promptChars, int width, int height, int jpegBytes) {
        if (terminal) return;
        if (promptChars >= 0 && promptChars <= 4000 && !values.containsKey("promptChars"))
            values.put("promptChars", promptChars);
        if ((width == 0 && height == 0 || width >= 1 && width <= 16384 && height >= 1 && height <= 16384)
                && !values.containsKey("referenceWidth") && !values.containsKey("referenceHeight")) {
            values.put("referenceWidth", width); values.put("referenceHeight", height);
        }
        if (jpegBytes >= 0 && jpegBytes <= 500 * 1024 && !values.containsKey("referenceJpegBytes"))
            values.put("referenceJpegBytes", jpegBytes);
    }
    synchronized void signalQueueBytes(long bytes) {
        if (!terminal && bytes >= 0 && bytes <= MAX_COUNTER) values.put("signalQueueBytes", bytes);
    }
    /** Actual queue sizes at the winning stop boundary; unavailable queues remain absent. */
    synchronized void signalBackpressure(String source, String processingType, int inbound, int decoded, int outbound, long bytes) {
        if (terminal || values.containsKey("backpressureSource") || source == null || !BACKPRESSURE_SOURCES.contains(source)) return;
        values.put("backpressureSource", source);
        values.put("processingType", safeProcessingType(processingType));
        metric("backpressureInboundPending", inbound, 0, 1000000);
        metric("backpressureDecodedMessages", decoded, 0, 1000000);
        metric("backpressureOutboundMessages", outbound, 0, 1000000);
        metric("backpressureOutboundBytes", bytes, 0, MAX_COUNTER);
    }
    static String safeProcessingType(String type) {
        return "none".equals(type) ? "none" : type != null && type.length() <= 64 && SIGNAL_COUNTS.containsKey(type) ? type : "unknown";
    }
    synchronized void mediaConnectedAt(long elapsedMs) {
        if (!terminal && elapsed(elapsedMs) && !values.containsKey("mediaConnectedAtMs")) values.put("mediaConnectedAtMs", elapsedMs);
    }
    /** Presence is reported separately: missing RTP fields remain unknown in completeStats. */
    synchronized void statsReport(long sampledAtElapsedMs, int inboundRtpReports, int inboundVideoReports,
                                  int inspected, boolean truncated) {
        if (terminal || !elapsed(sampledAtElapsedMs) || inspected < 0 || inspected > 256
                || inboundRtpReports < 0 || inboundRtpReports > inspected
                || inboundVideoReports < 0 || inboundVideoReports > inboundRtpReports
                || values.get("statsSampledAtMs") instanceof Number
                    && sampledAtElapsedMs < ((Number) values.get("statsSampledAtMs")).longValue()) return;
        values.put("statsSampledAtMs", sampledAtElapsedMs);
        values.put("inboundRtpReportCount", inboundRtpReports); values.put("inboundVideoReportCount", inboundVideoReports);
        values.put("inboundVideoStatsPresent", inboundVideoReports > 0);
        values.put("statsInspectedCount", inspected); values.put("statsReportTruncated", truncated);
    }
    private static boolean elapsed(long value) { return value >= 0 && value <= MAX_ELAPSED_MS; }
    synchronized void websocketClosed(int code, boolean remote) {
        if (terminal) return;
        values.put("websocketCloseCode", code); values.put("websocketCloseRemote", remote);
    }
    synchronized void exception(Throwable error) { if (!terminal) values.put("exceptionKind", exceptionKind(error)); }
    synchronized void websocketException(Throwable error, boolean protocolException) {
        if (terminal) return;
        String kind = exceptionKind(error);
        values.put("exceptionKind", protocolException && "other".equals(kind) ? "protocol_error" : kind);
    }
    synchronized void protocolFailure() { if (!terminal) values.put("exceptionKind", "protocol_error"); }
    synchronized void connection(String state) { enumeration("connectionState", state, CONNECTION); }
    synchronized void ice(String state) { enumeration("iceConnectionState", state, ICE); }
    synchronized void gathering(String state) { enumeration("iceGatheringState", state, GATHERING); }
    private void enumeration(String key, String state, Set<String> allowed) {
        if (!terminal && state != null && allowed.contains(state)) values.put(key, state);
    }
    synchronized void remoteTrack() { if (!terminal) values.put("remoteTrackReceived", true); }
    /** Agora account mapping is not remote publication, subscription or frame readiness. */
    synchronized void agoraBotMapped() { if (!terminal) values.put("botMapped", true); }
    synchronized void agoraPublication(String publication, String source) {
        if (terminal || publication == null || source == null || !REMOTE_PUBLICATION.contains(publication) || !PUBLICATION_SOURCES.contains(source)) return;
        values.put("remotePublication", publication); values.put("remotePublicationSource", source);
        if ("published".equals(publication)) remoteTrack();
    }
    synchronized void agoraRemoteVideoState(int state, int reason, long elapsedMs) {
        if (terminal) return;
        String value = switch (state) { case 0 -> "stopped"; case 1 -> "starting"; case 2 -> "decoding";
            case 3 -> "frozen"; case 4 -> "failed"; default -> "unknown"; };
        values.put("remoteVideoState", value);
        metric("remoteVideoStateCode", state, 0, 255);
        metric("remoteVideoReasonCode", reason, 0, 65535);
        metric("remoteVideoStateElapsedMs", elapsedMs, 0, 60000);
        if (state==1 || state==2) agoraPublication("published", "video_state");
        else if (reason==5 || reason==7) agoraPublication("unpublished", "video_state");
    }
    synchronized void agoraSubscription(int previous, int state, long elapsedMs) {
        if (terminal) return;
        values.put("remoteSubscriptionPreviousState", subscriptionState(previous));
        values.put("remoteSubscriptionState", subscriptionState(state));
        metric("remoteSubscriptionElapsedMs", elapsedMs, 0, 60000);
    }
    private static String subscriptionState(int state) {
        return switch (state) { case 0 -> "idle"; case 1 -> "not_subscribed"; case 2 -> "subscribing";
            case 3 -> "subscribed"; default -> "unknown"; };
    }
    synchronized void agoraFirstDecoded(int width, int height, long elapsedMs) {
        if (terminal) return;
        values.put("remoteFirstDecoded", true); remoteTrack();
        metric("remoteDecodedWidth", width, 1, 16384); metric("remoteDecodedHeight", height, 1, 16384);
        metric("remoteFirstDecodedElapsedMs", elapsedMs, 0, 60000);
    }
    synchronized void agoraFirstRendered(long elapsedMs) {
        if (terminal) return;
        values.put("remoteFirstRendered", true); remoteTrack(); metric("remoteFirstRenderedElapsedMs", elapsedMs, 0, 60000);
    }
    /** Raw observer entry buckets; only accepted/before-start/busy buckets establish the bound bot's video. */
    synchronized void agoraObserverFrame(String gate) {
        String counter = gate == null ? null : OBSERVER_GATES.get(gate);
        if (terminal || counter == null) return;
        increment("remoteObserverFrames"); increment(counter);
        if ("accepted".equals(gate) || "before_start".equals(gate) || "busy".equals(gate)) {
            remoteTrack(); increment("remoteTargetObserverFrames");
        }
        values.put("remoteObserverLastGate", gate);
    }
    synchronized void agoraStreamMessage() { increment("agoraStreamMessages"); }
    synchronized void agoraTerminalMessage() { increment("agoraTerminalMessages"); }
    synchronized void agoraUplinkStats(long sampledAtMs, int bitrate, int sentFps, int encodedFps, int lossPct) {
        if (terminal || !sampleAllowed("agoraUplinkStatsAtMs", sampledAtMs)) return;
        increment("agoraUplinkStatsSamples"); metric("agoraUplinkStatsAtMs", sampledAtMs, 0, 60000);
        metric("agoraSentBitrateKbps", bitrate, 0, 1000000); metric("agoraSentFps", sentFps, 0, 240);
        metric("agoraEncoderFps", encodedFps, 0, 240); metric("agoraTxPacketLossPct", lossPct, 0, 100);
    }
    synchronized void agoraDownlinkStats(long sampledAtMs, int bitrate, int decoderFps, int rendererFps, int lossPct) {
        if (terminal || !sampleAllowed("agoraDownlinkStatsAtMs", sampledAtMs)) return;
        increment("agoraDownlinkStatsSamples"); metric("agoraDownlinkStatsAtMs", sampledAtMs, 0, 60000);
        metric("agoraReceivedBitrateKbps", bitrate, 0, 1000000); metric("agoraDecoderFps", decoderFps, 0, 240);
        metric("agoraRendererFps", rendererFps, 0, 240); metric("agoraRxPacketLossPct", lossPct, 0, 100);
    }
    private boolean sampleAllowed(String key, long sampledAtMs) {
        Object previous = values.get(key);
        return elapsed(sampledAtMs) && (!(previous instanceof Number) || sampledAtMs >= ((Number) previous).longValue());
    }
    private void metric(String key, long value, long minimum, long maximum) {
        if (value >= minimum && value <= maximum) values.put(key, value);
    }

    synchronized void initialIceServers(int count) {
        if (!terminal && count >= 0 && count <= 16 && !values.containsKey("initialIceServerCount")) values.put("initialIceServerCount", count);
    }
    synchronized void initialReferenceProvided(boolean provided) {
        if (!terminal && !values.containsKey("initialReferenceProvided")) values.put("initialReferenceProvided", provided);
    }
    synchronized void initialSendResult(boolean sent) {
        if (terminal || Boolean.TRUE.equals(values.get("initialSignalSendAttempted"))) return;
        values.put("initialSignalSendAttempted", true); values.put("initialSignalSent", sent);
    }
    synchronized void initialAcknowledgement(String type, Object success) {
        if (terminal) return;
        String key = "prompt_ack".equals(type) ? "initialPromptAck" : "set_image_ack".equals(type) ? "initialImageAck" : null;
        if (key == null || !"not_received".equals(values.get(key))) return;
        values.put(key, Boolean.TRUE.equals(success) ? "accepted" : Boolean.FALSE.equals(success) ? "rejected" : "invalid");
    }
    synchronized void outboundCodec(String mime) {
        if (terminal) return;
        String codec = "unknown";
        if (mime != null && mime.length() <= 64) switch (mime.toLowerCase(Locale.ROOT)) {
            case "video/vp8": codec = "VP8"; break;
            case "video/vp9": codec = "VP9"; break;
            case "video/av1": codec = "AV1"; break;
            case "video/h264": codec = "H264"; break;
            case "video/h265": case "video/hevc": codec = "H265"; break;
            default: break;
        }
        values.put("outboundCodec", codec);
    }
    synchronized void inputFrame() { increment("inputFrames"); }
    synchronized void inputFrame(int width, int height, long capturedNs) {
        if (terminal) return;
        increment("inputFrames");
        if (width > 0 && width <= 1920 && height > 0 && height <= 1920) {
            values.put("inputWidth", width); values.put("inputHeight", height);
        }
        if (capturedNs < 0 || lastInputNs >= 0 && capturedNs < lastInputNs) return;
        if (firstInputNs < 0) firstInputNs = capturedNs;
        lastInputNs = capturedNs;
        long span = (capturedNs - firstInputNs) / 1000000L;
        if (span <= 60000) values.put("inputFrameSpanMs", span);
    }
    synchronized void remoteFrame() { increment("remoteFrames"); }
    synchronized void jpegFrame() { increment("jpegFrames"); }
    synchronized void idleTimeout() { increment("platformIdleTimeouts"); }
    private void increment(String key) {
        if (terminal) return;
        long old = ((Number) values.getOrDefault(key, 0L)).longValue();
        values.put(key, old < MAX_COUNTER ? old + 1 : MAX_COUNTER);
    }

    /** A missing callback keeps this one request outstanding; no speculative second native request. */
    synchronized boolean beginStats(long now) {
        if (terminal || statsPending || now < 0 || lastStatsRequest >= 0 && (now < lastStatsRequest || now - lastStatsRequest < CHECKPOINT_MS))
            return false;
        lastStatsRequest = now; statsPending = true; values.put("statsReadState", "pending"); return true;
    }
    synchronized void completeStats(Map<String, ? extends Number> counters) {
        if (terminal || !statsPending) return;
        // A successful report is a new observation; absent fields are unknown rather than stale or invented zeroes.
        for (String key : COUNTERS) values.remove(key);
        for (String key : GAUGES) values.remove(key);
        if (counters != null) {
            for (String key : COUNTERS) {
                Long value = integer(counters.get(key));
                if (value != null) values.put(key, value);
            }
            for (String key : GAUGES) {
                long maximum = "availableOutgoingBitrate".equals(key) ? MAX_COUNTER : "remoteFractionLost".equals(key) ? 1 : 60000;
                Double value = gauge(counters.get(key), maximum);
                if (value != null) values.put(key, value);
            }
        }
        statsPending = false; values.put("statsReadState", "ready");
    }
    synchronized void failStats(Throwable error) {
        if (terminal || !statsPending) return;
        statsPending = false; values.put("statsReadState", "failed"); values.put("statsErrorKind", exceptionKind(error));
    }

    /** Only video RTP numeric counters; no ICE addresses, certificates, codec strings or track identifiers. */
    static void collectStats(Map<String, Long> totals, String type, Map<String, Object> members) {
        if (totals == null || members == null || !("video".equals(members.get("kind")) || "video".equals(members.get("mediaType")))) return;
        String[] fields;
        if ("outbound-rtp".equals(type)) fields = new String[]{"bytesSent", "framesSent", "framesEncoded", "packetsSent"};
        else if ("inbound-rtp".equals(type)) fields = new String[]{"bytesReceived", "framesReceived", "framesDecoded", "packetsReceived", "packetsLost", "framesDropped"};
        else return;
        for (String field : fields) {
            Long value = integer(members.get(field));
            if (value == null) continue;
            long old = totals.getOrDefault(field, 0L);
            totals.put(field, old > MAX_COUNTER - value ? MAX_COUNTER : old + value);
        }
    }

    /** Caller supplies actual video remote-inbound-rtp members, never a local outbound guess. */
    static void collectRemoteInboundStats(Map<String, Number> totals, Map<String, Object> members) {
        if (totals == null || members == null || !("video".equals(members.get("kind")) || "video".equals(members.get("mediaType")))) return;
        sum(totals, "remotePacketsLost", members.get("packetsLost"));
        sum(totals, "remotePacketsReceived", members.get("packetsReceived"));
        sum(totals, "remoteRoundTripTimeMeasurements", members.get("roundTripTimeMeasurements"));
        sum(totals, "remoteReportsReceived", members.get("reportsReceived"));
        Double fraction = gauge(members.get("fractionLost"), 1);
        if (fraction != null) {
            Double previous = gauge(totals.get("remoteFractionLost"), 1);
            totals.put("remoteFractionLost", previous == null ? fraction : Math.max(previous, fraction));
        }
        Double seconds = gauge(members.get("roundTripTime"), 60);
        if (seconds != null) {
            double millis = seconds * 1000d;
            Double previous = gauge(totals.get("remoteRoundTripTimeMs"), 60000);
            totals.put("remoteRoundTripTimeMs", previous == null ? millis : Math.max(previous, millis));
        }
    }
    /** Selection is resolved by the caller from transport.selectedCandidatePairId; no addresses or IDs escape. */
    static void collectSelectedCandidatePairStats(Map<String, Number> totals, Map<String, Object> members) {
        if (totals == null || members == null) return;
        Long sent = integer(members.get("bytesSent")), received = integer(members.get("bytesReceived"));
        if (sent != null) totals.put("candidatePairBytesSent", sent);
        if (received != null) totals.put("candidatePairBytesReceived", received);
        Double seconds = gauge(members.get("currentRoundTripTime"), 60);
        if (seconds != null) totals.put("currentRoundTripTimeMs", seconds * 1000d);
        Double bitrate = gauge(members.get("availableOutgoingBitrate"), MAX_COUNTER);
        if (bitrate != null) totals.put("availableOutgoingBitrate", bitrate);
    }
    private static void sum(Map<String, Number> totals, String key, Object raw) {
        Long value = integer(raw);
        if (value == null) return;
        Long previous = integer(totals.get(key)); long old = previous == null ? 0 : previous;
        totals.put(key, old > MAX_COUNTER - value ? MAX_COUNTER : old + value);
    }
    private static Long integer(Object raw) {
        Double value = gauge(raw, MAX_COUNTER);
        if (value == null || value != Math.rint(value)) return null;
        return ((Number) raw).longValue();
    }
    private static Double gauge(Object raw, long maximum) {
        if (!(raw instanceof Number)) return null;
        double value = ((Number) raw).doubleValue();
        return Double.isFinite(value) && value >= 0 && value <= maximum ? value : null;
    }

    /** Exact codes only. Reason metadata stays separate from the actual platform error envelope. */
    synchronized void serverError(Map<String, Object> message, String envelope) {
        if (terminal) return;
        if (envelope != null && Set.of("platform", "status", "model", "control").contains(envelope)) values.put("serverErrorEnvelope", envelope);
        String[] result = {"unknown", "OTHER"};
        String[] reason = {"unknown", "OTHER"};
        int[] reasonMetadata = {0, -1};
        machineCodes(message, 0, new int[]{0}, result);
        reasonCodes(message, 0, new int[]{0}, reason, reasonMetadata, false);
        values.put("serverErrorKind", result[0]); values.put("serverErrorCode", result[1]);
        values.put("serverReasonPresent", reasonMetadata[0] != 0);
        values.put("serverReasonKind", reason[0]);
        values.remove("serverReasonChars");
        if (reasonMetadata[1] >= 0) values.put("serverReasonChars", reasonMetadata[1]);
    }
    private static void machineCodes(Object source, int depth, int[] nodes, String[] result) {
        if (source == null || depth > 3 || nodes[0]++ >= 16) return;
        if (source instanceof String) {
            classifyMachineCode((String) source, result, false);
            return;
        }
        if (!(source instanceof Map<?, ?>)) return;
        Map<?, ?> map = (Map<?, ?>) source;
        for (String key : new String[]{"code", "error_code", "error", "detail"})
            machineCodes(map.get(key), depth + 1, nodes, result);
    }
    /** Independent budget: an unfamiliar reason cannot hide the real platform error code. */
    private static void reasonCodes(Object source, int depth, int[] nodes, String[] result,
                                    int[] reasonMetadata, boolean reasonContext) {
        if (source == null || depth > 3 || nodes[0]++ >= 16) return;
        if (source instanceof String) {
            if (reasonContext) classifyMachineCode((String) source, result, true);
            return;
        }
        if (!(source instanceof Map<?, ?>)) return;
        Map<?, ?> map = (Map<?, ?>) source;
        if (map.containsKey("reason")) {
            Object raw = map.get("reason");
            if (reasonMetadata[0] == 0) {
                reasonMetadata[0] = 1;
                // First encountered reason only; a capped size does not reveal its contents.
                if (raw instanceof String) reasonMetadata[1] = Math.min(4096, ((String) raw).length());
            }
            reasonCodes(raw, depth + 1, nodes, result, reasonMetadata, true);
        }
        for (String key : new String[]{"code", "error_code", "error", "detail"})
            reasonCodes(map.get(key), depth + 1, nodes, result, reasonMetadata, reasonContext);
    }
    private static void classifyMachineCode(String value, String[] result, boolean reasonContext) {
        if (value.length() > 64) return;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (SERVER_KINDS.contains(normalized)) result[0] = normalized;
        if (reasonContext && FalSignalPolicy.concurrentSessionLimitCode(value)) result[0] = "concurrent_session_limit";
        if ("unauthorized".equals(normalized) || "unauthenticated".equals(normalized)) { result[0] = "auth_failed"; result[1] = "Unauthorized"; }
        else if ("timeout".equals(normalized)) { result[1] = "TIMEOUT"; if (reasonContext) result[0] = "timeout"; }
        else if ("internal_error".equals(normalized)) { result[0] = "service_internal_error"; result[1] = "INTERNAL_ERROR"; }
    }
    synchronized void finish(String failure) {
        if (terminal) return;
        terminal = true;
        String safe = failure == null ? "none" : FAILURES.contains(failure) ? failure : "unknown_failure";
        values.put("errorCode", safe);
        values.put("state", failure == null || "session_limit".equals(failure) ? "closed" : "failed");
    }
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        if (snapshotRevision < MAX_COUNTER) snapshotRevision++;
        copy.put("diagnosticRevision", snapshotRevision);
        return Collections.unmodifiableMap(copy);
    }

    static String exceptionKind(Throwable error) {
        String kind = "other"; int priority = 0;
        for (int depth = 0; error != null && depth < 5; depth++) {
            int next; String candidate;
            if (error instanceof LinkageError) { next = 9; candidate = "native_linkage"; }
            else if (error instanceof SSLException || error instanceof CertificateException) { next = 8; candidate = "tls_failed"; }
            else if (error instanceof UnknownHostException) { next = 7; candidate = "dns_failed"; }
            else if (error instanceof SocketTimeoutException || error instanceof InterruptedIOException) { next = 6; candidate = "timeout"; }
            else if (error instanceof NoRouteToHostException) { next = 5; candidate = "unreachable"; }
            else if (error instanceof ConnectException) { next = 4; candidate = "connect_failed"; }
            else if (error instanceof ProtocolException) { next = 3; candidate = "protocol_error"; }
            else if (error instanceof IOException) { next = 2; candidate = "io_error"; }
            else { next = 1; candidate = "other"; }
            if (next > priority) { priority = next; kind = candidate; }
            try { error = error.getCause(); } catch (RuntimeException ignored) { break; }
        }
        return kind;
    }
}
