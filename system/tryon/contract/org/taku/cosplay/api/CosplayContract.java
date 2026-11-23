package org.taku.cosplay.api;

/** Bounded, local handoff from a real system-assistant invocation to Taku cosplay. */
public final class CosplayContract {
    public static final String SCHEMA = "taku.cosplay.voice.v1";
    public static final String AUTHORITY = "org.taku.tryon.api";
    public static final String ASSISTANT_PACKAGE = "org.agentos.agent";
    /** Approved development signer; production accepts the installed Android platform signer. */
    public static final String ASSISTANT_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    public static final String TRYON_PACKAGE = "org.taku.tryon";
    public static final String TRYON_CERT_SHA256 = "093755c8c6f8c0fd1fd9ceab905f53c139e257e1d019942faebcc6f30448c9f5";
    public static final String STATUS = "cosplayStatus";
    /** Additive metadata; the legacy status envelope stays Assistant-compatible. */
    public static final String MODEL_STATUS = "cosplayModelStatus";
    public static final String PREPARE = "prepareCosplay";
    /** Opens the existing clothing selection; it never starts camera/cloud processing. */
    public static final String PREPARE_OUTFIT = "prepareCosplayOutfit";
    public static final String COMMAND = "cosplay_current_video_v1";
    public static final String CANCEL = "cancelCosplay";
    public static final String CONFIGURE = "configureCosplay";
    public static final String REPORT_VOICE = "reportCosplayVoice";
    public static final String VOICE_DIAGNOSTICS = "cosplayVoiceDiagnostics";
    public static final String SETUP_ACTIVITY = "org.taku.tryon.realtime.TryOnActivity";
    public static final String SPEECH_SETTINGS_SCOPE = "assistant-entry";
    public static final String SPEECH_SETTINGS_PACKAGE = "org.agentos.audio";
    public static final String SPEECH_SETTINGS_ACTIVITY = "org.agentos.audio.DictationSettingsActivity";
    public static final String ACTIVITY = "org.taku.tryon.realtime.DirectCosplayActivity";
    public static final String EXTRA_TICKET = "org.taku.cosplay.TICKET";
    public static final int MAX_JPEG_BYTES = 320 * 1024;
    public static final int MAX_IMAGE_EDGE = 1920;
    /** The original selected reference can survive a bounded clarification/retry. */
    public static final long MAX_CAPTURE_AGE_MS = 120_000;
    public static final long MAX_INVOCATION_AGE_MS = 120_000;
    public static final long CLARIFICATION_WAIT_MS = 45_000;
    public static final long TICKET_TTL_MS = 15_000;
    public static final int MAX_PENDING = 2;
    private CosplayContract() { }
}
