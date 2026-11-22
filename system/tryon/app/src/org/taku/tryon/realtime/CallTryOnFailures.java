// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Fixed, non-sensitive product messages. Never includes provider response bodies or exception text. */
final class CallTryOnFailures {
    private static final java.util.Set<String> PREVIEW_HOST_FAILURES = java.util.Set.of(
            "preview_host_failed", "system_island_timeout", "system_island_unavailable", "system_island_disconnected",
            "system_island_binding_died", "system_island_bind_failed", "system_island_layout_failed", "system_island_error",
            "system_island_invalid_display", "system_island_missing_surface", "system_island_attach_failed", "system_island_connection_lost");
    private CallTryOnFailures() {}
    static String previewHostCode(String code) {
        return code != null && PREVIEW_HOST_FAILURES.contains(code) ? code : "preview_host_failed";
    }
    static String token(String code, int status) {
        if ("missing_key".equals(code)) return "Save your fal Key in connection settings first.";
        if ("token_auth_failed".equals(code)) return "fal authorization failed. Check your saved key and account access.";
        if ("token_invalid_response".equals(code)) return "fal returned an invalid authorization response. Try-on has not started. Try again later.";
        if ("token_cancelled".equals(code)) return "Cloud authorization was cancelled. Try-on has not started.";
        if ("token_http_failed".equals(code)) {
            if (status == 429) return "Too many fal authorization requests. Try again later.";
            if (status == 402) return "Your fal balance or billing is not ready. Check your account and try again.";
            if (status >= 500 && status <= 599) return "fal authorization is temporarily unavailable. Try-on has not started. Try again later.";
            return "fal rejected this authorization request. Try-on has not started. Check your account and service settings.";
        }
        if ("token_network_failed".equals(code)) return "Could not reach fal authorization. Check your network and try again.";
        return "Could not authorize cloud access. Try-on has not started. Try again later.";
    }
    static boolean normalCloudEnd(String code) { return "session_limit".equals(code); }
    static String cloud(String code, java.util.Map<String, Object> evidence) {
        String preparation = preparationFailure(code, evidence);
        if (preparation != null) return preparation;
        if ("fal_endpoint_error".equals(code) && evidence != null
                && "INTERNAL_ERROR".equals(evidence.get("serverErrorCode")))
            return "Cloud service error (INTERNAL_ERROR). Try-on stopped. Please try again later.";
        return cloud(code);
    }
    private static String preparationFailure(String code, java.util.Map<String, Object> evidence) {
        if (evidence == null || !("invalid_signal".equals(code) || "model_reported_error".equals(code)
                || "cloud_auth_failed".equals(code))) return null;
        Object step = evidence.get("providerStep");
        boolean preparing = "temporary_key".equals(step) || "session_create".equals(step)
                || "session_parse".equals(step) || "reference_sts".equals(step) || "reference_upload".equals(step);
        if (!preparing) return null;
        if ("cloud_auth_failed".equals(code))
            return "XMax authorization failed. Check your saved key and selected region.";
        if ("network_failure".equals(evidence.get("providerFailureKind")))
            return "Could not reach XMax while preparing try-on. Check your connection and try again.";
        Object status = evidence.get("providerHttpStatus");
        if (status instanceof Number) {
            int http = ((Number) status).intValue();
            if (http == 429) return "XMax is receiving too many requests. Please try again later.";
            if (http >= 500 && http <= 599) return "XMax is temporarily unavailable. Please try again later.";
        }
        if (!"invalid_signal".equals(code) && !"model_reported_error".equals(code)) return null;
        if ("temporary_key".equals(step)) return "XMax could not prepare authorization. Check connection diagnostics before retrying.";
        if ("session_create".equals(step) || "session_parse".equals(step))
            return "XMax could not prepare a valid video session. No try-on video was generated.";
        if ("reference_sts".equals(step)) return "XMax could not prepare the outfit image upload. Check connection diagnostics before retrying.";
        return "XMax could not receive the outfit image. Check connection diagnostics before retrying.";
    }
    static String cloud(String code) {
        if (normalCloudEnd(code)) return "The 60-second preview ended. Camera is off.";
        if ("lucy_session_busy".equals(code)) return "Another Lucy session is still closing or your account has reached its session limit. Wait a moment, then start again manually.";
        if ("cloud_auth_failed".equals(code)) return "Cloud try-on authorization failed. Disconnected.";
        if ("model_control_rejected".equals(code)) return "The model rejected this outfit description or reference image. Adjust it and try again.";
        if ("first_frame_timeout".equals(code)) return "No cloud video arrived within 30 seconds. Try-on stopped. Please try again later.";
        if ("return_frame_failed".equals(code)) return "Could not process the returned video. Disconnected. Start again to retry.";
        if ("input_frame_failed".equals(code)) return "Could not process camera video. Disconnected. Start again to retry.";
        if ("invalid_signal".equals(code) || "invalid_request".equals(code)) return "The try-on service returned invalid data. Disconnected. Check component versions.";
        if ("fal_endpoint_error".equals(code) || "model_reported_error".equals(code)) return "The cloud model reported a processing failure. Disconnected. Try again later.";
        if ("peer_failed".equals(code) || "peer_negotiation_failed".equals(code)) return "Cloud video negotiation failed. Try-on stopped. Check your network and try again.";
        if ("signal_backpressure".equals(code)) return "Try-on stopped because video processing could not keep up.";
        if ("websocket_closed".equals(code)) return "The cloud signaling connection closed. Try-on stopped. Check connection diagnostics for the stage.";
        if ("websocket_error".equals(code) || "websocket_open_failed".equals(code))
            return "Cloud signaling failed. Try-on stopped. Check connection diagnostics before retrying.";
        if ("peer_creation_failed".equals(code) || "rtc_internal_error".equals(code))
            return "Could not set up live video on this device. Try-on stopped. Check connection diagnostics.";
        if ("restore_unconfirmed".equals(code)) return "Disconnected, but camera restoration is not confirmed. Stop the camera before retrying.";
        return "The live connection could not continue. Try-on stopped. Check connection diagnostics before retrying.";
    }
    static String safeCode(String code) {
        if (code == null) return "unknown_failure";
        if (PREVIEW_HOST_FAILURES.contains(code)) return code;
        switch (code) {
            case "none": case "unknown_failure":
            case "invalid_reference": case "overlay_permission_required": case "foreground_service_denied":
            case "screen_off": case "user_stop": case "service_destroyed":
            case "bridge_invalid_duration": case "bridge_invalid_frame": case "bridge_invalid_return": case "bridge_unavailable":
            case "websocket_closed": case "websocket_error": case "websocket_open_failed":
            case "rtc_internal_error": case "peer_creation_failed": case "listener_failed":
            case "invalid_ice_restart": case "ice_restart_limit": case "too_many_ice_candidates":
            case "rtc_start_failed": case "source_identity_changed": case "segment_failure":
            case "session_failure": case "restore_unconfirmed": case "duration_complete":
            case "session_wall_limit": case "camera_wait_timeout": case "handoff_prepare_timeout":
            case "handoff_wait_timeout": case "camera_stalled": case "camera_stopped":
            case "missing_key": case "token_auth_failed": case "token_invalid_response": case "token_cancelled":
            case "token_http_failed": case "token_network_failed": case "session_limit": case "lucy_session_busy": case "cloud_auth_failed":
            case "model_control_rejected": case "first_frame_timeout": case "return_frame_failed": case "input_frame_failed":
            case "invalid_signal": case "invalid_request": case "fal_endpoint_error": case "model_reported_error":
            case "peer_failed": case "peer_negotiation_failed": case "signal_backpressure": return code;
            default:
                return "unknown_failure";
        }
    }
}
