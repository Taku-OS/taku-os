// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private, bounded last-session metadata. Never persists media, credentials or arbitrary text. */
public final class CallTryOnDiagnosticStore {
    private static final String SCHEMA = "taku.call-tryon-diagnostic.v1";
    private static final int MAX_BYTES = 8192;
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;
    // Provider reads may construct a different Store from the Service writer in the same process.
    private static final Object FILE_LOCK = new Object();
    private static final Set<String> PROVIDERS = values("lucy", "xmax");
    private static final Set<String> ENVIRONMENTS = values("china", "global");
    private static final Set<String> MODES = values("shopping", "preview", "call", "widget");
    private static final Set<String> PHASES = values("preview", "call", "awaiting_camera", "stopping", "stopped", "unknown");
    private static final Set<String> STATES = values("stopped", "starting", "waiting_camera", "handoff_waiting",
            "handoff_preparing", "authorizing", "connecting", "running", "stopping", "failed", "widget_idle",
            "mock", "shopping_mock", "unknown");
    private static final Set<String> RTC_STAGES = values("opening", "signaling_connected", "peer_creating", "offer_sent",
            "answer_received", "media_connected", "generation_started", "video_live", "unknown");
    private static final Set<String> STAGES = values("source_wait", "source_ready", "token_pending", "token_ready",
            "rtc_connecting", "opening", "signaling_connected", "peer_creating", "offer_sent", "answer_received",
            "media_connected", "generation_started", "video_live", "unknown");
    private static final Set<String> RTC_STATES = values("idle", "opening", "connecting", "signaling_connected",
            "peer_creating", "offer_sent", "answer_received", "media_connected", "generation_started", "video_live",
            "remote_track", "ice_restart_offer_sent", "live", "closed", "failed", "stopped", "unknown");
    private static final Set<String> EXCEPTIONS = values("dns_failed", "timeout", "connect_failed", "tls_failed",
            "unreachable", "io_error", "protocol_error", "native_linkage", "other");
    private static final Set<String> STATS_STATES = values("not_started", "pending", "ready", "failed");
    private static final Set<String> CONNECTION = values("new", "connecting", "connected", "disconnected", "failed", "closed");
    private static final Set<String> ICE_CONNECTION = values("new", "checking", "connected", "completed", "disconnected", "failed", "closed");
    private static final Set<String> ICE_GATHERING = values("new", "gathering", "complete");
    private static final Set<String> ROUTES = values("direct", "http_connect_proxy", "socks_proxy");
    private static final Set<String> TRANSPORT_STAGES = values("proxy_connecting", "proxy_tunneling",
            "tls_handshaking", "websocket_handshaking", "connected");
    private static final Set<String> PROVIDER_STEPS = values("temporary_key", "session_create", "session_parse",
            "reference_sts", "reference_upload", "heartbeat", "cleanup", "unknown");
    private static final Set<String> PROVIDER_FAILURE_KINDS = values("malformed_json", "missing_data", "response_too_large",
            "business_rejected", "missing_temporary_key", "missing_session_id", "invalid_model_extra",
            "missing_room_id", "missing_rtc_user_id", "missing_room_token", "missing_bot_id", "missing_app_id",
            "app_id_mismatch", "inactive_session", "invalid_sts_bucket", "invalid_sts_region", "invalid_sts_prefix",
            "missing_sts_credentials", "invalid_sts_access_key", "invalid_sts_secret_key", "invalid_sts_token",
            "http_rejected", "network_failure", "invalid_reference", "cancelled", "unknown");
    private static final Set<String> REMOTE_PUBLICATIONS = values("published", "unpublished");
    private static final Set<String> PUBLICATION_SOURCES = values("user_offline", "video_state");
    private static final Set<String> REMOTE_VIDEO_STATES = values("stopped", "starting", "decoding", "frozen", "failed", "unknown");
    private static final Set<String> SUBSCRIPTION_STATES = values("idle", "not_subscribed", "subscribing", "subscribed", "unknown");
    private static final Set<String> OBSERVER_GATES = values("accepted", "before_start", "busy", "stopped", "wrong_room", "unmapped_bot", "wrong_bot");
    private static final Set<String> ACKS = values("not_received", "accepted", "rejected", "invalid");
    private static final Set<String> CODECS = values("VP8", "VP9", "AV1", "H264", "H265", "unknown");
    private static final Set<String> SIGNAL_TYPES = values("ready", "iceservers", "answer", "icecandidate",
            "ice-restart", "prompt_ack", "set_image_ack", "generation_started", "error", "x-fal-error",
            "x-fal-message", "unknown");
    private static final Set<String> SERVER_KINDS = values("media_connection_timeout", "media_connection_failed",
            "input_frames_timeout", "invalid_reference", "reference_required", "invalid_prompt", "unsupported_video",
            "auth_failed", "quota_exceeded", "rate_limited", "service_unavailable", "service_internal_error",
            "content_rejected", "unknown");
    private static final Set<String> SERVER_CODES = values("TIMEOUT", "INTERNAL_ERROR", "Unauthorized", "OTHER");
    private static final Set<String> SERVER_REASON_KINDS = values("media_connection_timeout", "media_connection_failed",
            "input_frames_timeout", "invalid_reference", "reference_required", "invalid_prompt", "unsupported_video",
            "auth_failed", "quota_exceeded", "rate_limited", "service_unavailable", "service_internal_error",
            "content_rejected", "concurrent_session_limit", "timeout", "unknown");
    private static final Set<String> SERVER_ENVELOPES = values("platform", "status", "model", "control");
    private final AtomicFile file;

    public CallTryOnDiagnosticStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "call-tryon-last-diagnostic.json"));
    }

    /** Synchronous and atomic; callers schedule sparse saves on their own single writer executor. */
    public void save(JSONObject source) {
        synchronized (FILE_LOCK) {
            FileOutputStream output = null;
            try {
                byte[] bytes = sanitize(source).toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) return;
                output = file.startWrite();
                output.write(bytes);
                file.finishWrite(output);
            } catch (Exception ignored) {
                if (output != null) {
                    try { file.failWrite(output); } catch (RuntimeException alsoFailed) { }
                }
                // A diagnostic failure must neither stop a session nor disclose an IO exception.
            }
        }
    }

    /** Does not start or bind a service. Data read from disk passes the same whitelist as new data. */
    public JSONObject read() {
        synchronized (FILE_LOCK) {
            try (FileInputStream input = file.openRead()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
                byte[] chunk = new byte[1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (count > MAX_BYTES - bytes.size()) return empty();
                    bytes.write(chunk, 0, count);
                }
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                if (!boundedJson(text)) return empty();
                JSONTokener parser = new JSONTokener(text);
                Object raw = parser.nextValue();
                if (!(raw instanceof JSONObject) || parser.nextClean() != 0) return empty();
                JSONObject object = (JSONObject) raw;
                if (!SCHEMA.equals(object.opt("schema"))) return empty();
                JSONObject result = sanitize(object);
                result.put("exists", true);
                return result;
            } catch (Exception ignored) {
                return empty();
            }
        }
    }

    /** Rebuilds a fixed schema; even nested Maps are read one allowed scalar at a time. */
    public static JSONObject sanitize(JSONObject source) {
        JSONObject result = schema();
        try {
            Source input = sourceOf(source);
            for (String key : new String[]{"startedAtMs", "capturedAtMs", "elapsedMs"})
                integer(result, input, key, 0, MAX_INTEGER);
            for (String key : new String[]{"attempt", "segment", "inputs", "outputs", "recoveries"})
                integer(result, input, key, 0, Integer.MAX_VALUE);
            enumeration(result, input, "provider", PROVIDERS);
            enumeration(result, input, "environment", ENVIRONMENTS);
            enumeration(result, input, "mode", MODES);
            enumeration(result, input, "phase", PHASES);
            enumeration(result, input, "state", STATES);
            enumeration(result, input, "stage", STAGES);
            failure(result, input, "failureCode");
            http(result, input, "tokenHttpStatus");
            bool(result, input, "terminal");
            bool(result, input, "restoreConfirmed");
            bool(result, input, "cloudCleanupConfirmed");
            Object rawRtc = input.get("rtc");
            if (rawRtc instanceof JSONObject || rawRtc instanceof Map<?, ?>) {
                Source rtcInput = sourceOf(rawRtc);
                JSONObject rtc = new JSONObject();
                enumeration(rtc, rtcInput, "stage", RTC_STAGES);
                enumeration(rtc, rtcInput, "state", RTC_STATES);
                failure(rtc, rtcInput, "errorCode");
                enumeration(rtc, rtcInput, "exceptionKind", EXCEPTIONS);
                enumeration(rtc, rtcInput, "statsReadState", STATS_STATES);
                enumeration(rtc, rtcInput, "statsErrorKind", EXCEPTIONS);
                enumeration(rtc, rtcInput, "connectionState", CONNECTION);
                enumeration(rtc, rtcInput, "iceConnectionState", ICE_CONNECTION);
                enumeration(rtc, rtcInput, "iceGatheringState", ICE_GATHERING);
                enumeration(rtc, rtcInput, "connectionRoute", ROUTES);
                enumeration(rtc, rtcInput, "transportStage", TRANSPORT_STAGES);
                enumeration(rtc, rtcInput, "providerStep", PROVIDER_STEPS);
                enumeration(rtc, rtcInput, "providerFailureKind", PROVIDER_FAILURE_KINDS);
                http(rtc, rtcInput, "providerHttpStatus");
                enumeration(rtc, rtcInput, "initialPromptAck", ACKS);
                enumeration(rtc, rtcInput, "initialImageAck", ACKS);
                enumeration(rtc, rtcInput, "outboundCodec", CODECS);
                enumeration(rtc, rtcInput, "lastSignalType", SIGNAL_TYPES);
                enumeration(rtc, rtcInput, "serverErrorKind", SERVER_KINDS);
                enumeration(rtc, rtcInput, "serverErrorCode", SERVER_CODES);
                enumeration(rtc, rtcInput, "serverErrorEnvelope", SERVER_ENVELOPES);
                enumeration(rtc, rtcInput, "serverReasonKind", SERVER_REASON_KINDS);
                bool(rtc, rtcInput, "serverReasonPresent");
                integer(rtc, rtcInput, "serverReasonChars", 0, 4096);
                enumeration(rtc, rtcInput, "remotePublication", REMOTE_PUBLICATIONS);
                enumeration(rtc, rtcInput, "remotePublicationSource", PUBLICATION_SOURCES);
                enumeration(rtc, rtcInput, "remoteVideoState", REMOTE_VIDEO_STATES);
                enumeration(rtc, rtcInput, "remoteSubscriptionPreviousState", SUBSCRIPTION_STATES);
                enumeration(rtc, rtcInput, "remoteSubscriptionState", SUBSCRIPTION_STATES);
                enumeration(rtc, rtcInput, "remoteObserverLastGate", OBSERVER_GATES);
                for (String key : new String[]{"botMapped", "remoteFirstDecoded", "remoteFirstRendered"}) bool(rtc, rtcInput, key);
                integer(rtc, rtcInput, "remoteVideoStateCode", 0, 255);
                integer(rtc, rtcInput, "remoteVideoReasonCode", 0, 65535);
                for (String key : new String[]{"remoteVideoStateElapsedMs", "remoteSubscriptionElapsedMs", "remoteFirstDecodedElapsedMs",
                        "remoteFirstRenderedElapsedMs", "agoraUplinkStatsAtMs", "agoraDownlinkStatsAtMs"}) integer(rtc, rtcInput, key, 0, 60000);
                for (String key : new String[]{"remoteDecodedWidth", "remoteDecodedHeight"}) integer(rtc, rtcInput, key, 1, 16384);
                for (String key : new String[]{"remoteObserverFrames", "remoteObserverAcceptedFrames", "remoteObserverBeforeStartDrops",
                        "remoteObserverBusyDrops", "remoteObserverStoppedDrops", "remoteObserverWrongRoomDrops", "remoteObserverUnmappedBotDrops",
                        "remoteObserverWrongBotDrops", "remoteTargetObserverFrames", "agoraStreamMessages", "agoraTerminalMessages", "agoraUplinkStatsSamples", "agoraDownlinkStatsSamples"})
                    integer(rtc, rtcInput, key, 0, MAX_INTEGER);
                for (String key : new String[]{"agoraSentBitrateKbps", "agoraReceivedBitrateKbps"}) integer(rtc, rtcInput, key, 0, 1000000);
                for (String key : new String[]{"agoraSentFps", "agoraEncoderFps", "agoraDecoderFps", "agoraRendererFps"}) integer(rtc, rtcInput, key, 0, 240);
                for (String key : new String[]{"agoraTxPacketLossPct", "agoraRxPacketLossPct"}) integer(rtc, rtcInput, key, 0, 100);
                for (String key : new String[]{"websocketOpened", "websocketCloseRemote", "remoteTrackReceived",
                        "offerSent", "answerReceived", "generationStarted", "initialSignalSendAttempted",
                        "initialSignalSent", "initialReferenceProvided", "inboundVideoStatsPresent",
                        "statsReportTruncated"}) bool(rtc, rtcInput, key);
                integer(rtc, rtcInput, "websocketCloseCode", 1000, 4999);
                http(rtc, rtcInput, "handshakeHttpStatus");
                http(rtc, rtcInput, "proxyConnectStatus");
                integer(rtc, rtcInput, "initialIceServerCount", 0, 32);
                integer(rtc, rtcInput, "inputWidth", 1, 1920);
                integer(rtc, rtcInput, "inputHeight", 1, 1920);
                integer(rtc, rtcInput, "referenceWidth", 0, 16384);
                integer(rtc, rtcInput, "referenceHeight", 0, 16384);
                integer(rtc, rtcInput, "referenceJpegBytes", 0, 512000);
                integer(rtc, rtcInput, "promptChars", 0, 4000);
                integer(rtc, rtcInput, "inputFrameSpanMs", 0, 60000);
                for (String key : new String[]{"lastSignalAtMs", "mediaConnectedAtMs", "statsSampledAtMs"})
                    integer(rtc, rtcInput, key, 0, 60000);
                integer(rtc, rtcInput, "initialPayloadBytes", 0, Integer.MAX_VALUE);
                for (String key : new String[]{"inboundRtpReportCount", "inboundVideoReportCount", "statsInspectedCount"})
                    integer(rtc, rtcInput, key, 0, 256);
                for (String key : new String[]{"inputFrames", "remoteFrames", "jpegFrames", "platformIdleTimeouts"})
                    integer(rtc, rtcInput, key, 0, Integer.MAX_VALUE);
                for (String key : new String[]{"diagnosticRevision", "bytesSent", "bytesReceived", "framesEncoded",
                        "framesDecoded", "packetsLost", "framesSent", "framesReceived", "packetsSent", "packetsReceived", "framesDropped",
                        "remotePacketsLost", "remotePacketsReceived", "remoteReportsReceived", "remoteRoundTripTimeMeasurements",
                        "candidatePairBytesSent", "candidatePairBytesReceived", "signalQueueBytes",
                        "signalMessagesReceived", "signalUnknownCount", "signalReadyCount", "signalIceServersCount",
                        "signalAnswerCount", "signalIceCandidateCount", "signalIceRestartCount", "signalPromptAckCount",
                        "signalSetImageAckCount", "signalGenerationStartedCount", "signalErrorCount",
                        "signalFalErrorCount", "signalFalMessageCount"})
                    integer(rtc, rtcInput, key, 0, MAX_INTEGER);
                decimal(rtc, rtcInput, "remoteRoundTripTimeMs", 0, 60000);
                decimal(rtc, rtcInput, "currentRoundTripTimeMs", 0, 60000);
                decimal(rtc, rtcInput, "availableOutgoingBitrate", 0, MAX_INTEGER);
                decimal(rtc, rtcInput, "remoteFractionLost", 0, 1);
                result.put("rtc", rtc);
            }
            return result;
        } catch (JSONException | RuntimeException invalid) {
            return schema();
        }
    }

    private interface Source { Object get(String key); }

    private static Source sourceOf(Object value) {
        if (value instanceof JSONObject) return ((JSONObject) value)::opt;
        if (value instanceof Map<?, ?>) return ((Map<?, ?>) value)::get;
        return key -> null;
    }

    private static void enumeration(JSONObject result, Source input, String key, Set<String> allowed) throws JSONException {
        Object value = input.get(key);
        if (value instanceof String && allowed.contains(value)) result.put(key, value);
    }

    private static void failure(JSONObject result, Source input, String key) throws JSONException {
        Object value = input.get(key);
        if (value instanceof String) result.put(key, CallTryOnFailures.safeCode((String) value));
    }

    private static void bool(JSONObject result, Source input, String key) throws JSONException {
        Object value = input.get(key);
        if (value instanceof Boolean) result.put(key, value);
    }

    private static void integer(JSONObject result, Source input, String key, long minimum, long maximum) throws JSONException {
        Object value = input.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return;
        long number = ((Number) value).longValue();
        if (number >= minimum && number <= maximum) result.put(key, number);
    }

    private static void http(JSONObject result, Source input, String key) throws JSONException {
        Object value = input.get(key);
        if (!(value instanceof Integer || value instanceof Long)) return;
        long number = ((Number) value).longValue();
        if (number >= 100 && number <= 599) result.put(key, number);
    }

    private static void decimal(JSONObject result, Source input, String key, double minimum, double maximum) throws JSONException {
        Object value = input.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double)) return;
        double number = ((Number) value).doubleValue();
        if (Double.isFinite(number) && number >= minimum && number <= maximum) result.put(key, number);
    }

    private static JSONObject schema() {
        JSONObject result = new JSONObject();
        try { result.put("schema", SCHEMA); } catch (JSONException impossible) { }
        return result;
    }

    private static JSONObject empty() {
        JSONObject result = schema();
        try { result.put("exists", false); } catch (JSONException impossible) { }
        return result;
    }

    /** Reject deep corrupt input before invoking a recursive platform JSON parser. */
    private static boolean boundedJson(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') { if (++depth > 8) return false; }
            else if (value == '}' || value == ']') { if (--depth < 0) return false; }
        }
        return depth == 0 && !quoted;
    }

    private static Set<String> values(String... items) { return new HashSet<>(Arrays.asList(items)); }
}
