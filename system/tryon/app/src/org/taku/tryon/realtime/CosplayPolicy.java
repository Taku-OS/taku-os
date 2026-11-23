// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.taku.tryon.realtime;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.taku.cosplay.api.CosplayContract;

/** Metadata for an actual assistant invocation, never a model-selected or inferred action. */
public final class CosplayPolicy {
    static final String TARGET_CHARACTER = "character";
    static final String TARGET_OUTFIT_SELECTION = "outfit_selection";
    private static final Set<String> FIELDS = Set.of("schema", "jpeg", "capturedAtElapsedMs",
            "invokedAtElapsedMs", "command", "sourcePackage", "requestId");
    private static final Pattern REQUEST_ID = Pattern.compile("[a-f0-9]{32}");
    public record Request(byte[] jpeg, long capturedAtElapsedMs, long invokedAtElapsedMs,
            String command, String sourcePackage, String requestId, String target) {
        public Request {
            if (!validTarget(target)) throw invalid();
        }
        public Request(byte[] jpeg, long capturedAtElapsedMs, long invokedAtElapsedMs,
                String command, String sourcePackage, String requestId) {
            this(jpeg, capturedAtElapsedMs, invokedAtElapsedMs, command, sourcePackage, requestId, TARGET_CHARACTER);
        }
        @Override public String toString() { return "ValidatedCosplayRequest"; }
    }

    private CosplayPolicy() { }

    /** These values come from Binder/PackageManager, not caller-provided JSON or Bundle fields. */
    public static boolean trustedAssistant(int uid, String callingPackage, int installedUid, String signer) {
        return trustedAssistant(uid,callingPackage,installedUid,signer,false);
    }
    public static boolean trustedAssistant(int uid,String callingPackage,int installedUid,String signer,boolean platformSigned) {
        return uid >= 10000 && installedUid == uid
                && CosplayContract.ASSISTANT_PACKAGE.equals(callingPackage)
                && (platformSigned || CosplayContract.ASSISTANT_CERT_SHA256.equals(signer));
    }

    public static Request validate(Map<String, Object> fields, long now) {
        return validateForTarget(fields, now, TARGET_CHARACTER);
    }
    /** Target is selected by the authenticated API method, never by input fields. */
    static Request validateForTarget(Map<String, Object> fields, long now, String target) {
        if (!validTarget(target)) throw invalid();
        if (fields == null || !fields.keySet().equals(FIELDS)
                || !CosplayContract.SCHEMA.equals(fields.get("schema"))) throw invalid();
        Object bytes = fields.get("jpeg");
        if (!(bytes instanceof byte[]) || ((byte[]) bytes).length < 4
                || ((byte[]) bytes).length > CosplayContract.MAX_JPEG_BYTES) throw invalid();
        byte[] jpeg = (byte[]) bytes;
        if ((jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216 || (jpeg[2] & 255) != 255
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
            throw invalid();
        Object captured = fields.get("capturedAtElapsedMs"), invoked = fields.get("invokedAtElapsedMs");
        if (!(captured instanceof Long) || !(invoked instanceof Long)) throw invalid();
        long capturedAt = (Long) captured, invokedAt = (Long) invoked;
        if (now < 0 || invokedAt < 0 || capturedAt < invokedAt || capturedAt > now
                || now - capturedAt >= CosplayContract.MAX_CAPTURE_AGE_MS
                || now - invokedAt >= CosplayContract.MAX_INVOCATION_AGE_MS) throw expired();
        Object command = fields.get("command"), source = fields.get("sourcePackage"), id = fields.get("requestId");
        // The trusted assistant recognizes the actual utterance. Raw ASR never crosses this API.
        if (!CosplayContract.COMMAND.equals(command)) throw invalid();
        if (!("com.zhiliaoapp.musically".equals(source) || "com.ss.android.ugc.trill".equals(source)))
            throw new IllegalArgumentException("Open the TikTok look you want to try, then ask again.");
        if (!(id instanceof String) || !REQUEST_ID.matcher((String) id).matches()) throw invalid();
        return new Request(jpeg, capturedAt, invokedAt, (String) command, (String) source, (String) id, target);
    }

    static boolean validTarget(String target) {
        return TARGET_CHARACTER.equals(target) || TARGET_OUTFIT_SELECTION.equals(target);
    }

    public static boolean imageDimensionsAllowed(int width, int height) {
        return width > 0 && height > 0 && width <= CosplayContract.MAX_IMAGE_EDGE
                && height <= CosplayContract.MAX_IMAGE_EDGE;
    }
    static boolean validTicket(String ticket) { return ticket != null && REQUEST_ID.matcher(ticket).matches(); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("This cosplay request is invalid. Ask again from the current TikTok look.");
    }
    private static IllegalArgumentException expired() {
        return new IllegalArgumentException("This cosplay request expired. Ask again from the current TikTok look.");
    }
}
