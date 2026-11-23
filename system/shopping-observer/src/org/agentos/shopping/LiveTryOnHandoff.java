// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.util.Base64;
import java.security.SecureRandom;
import org.taku.tryon.contract.LiveTryOnContract;

/** Explicit handoff to the native visible entry; no broadcast starts a camera or foreground service. */
final class LiveTryOnHandoff {
    static final String PACKAGE = LiveTryOnContract.TARGET_PACKAGE;
    private static final ComponentName DIRECT_ENTRY = new ComponentName(PACKAGE,
        "org.taku.tryon.realtime.DirectLiveTryOnActivity");
    private static final SecureRandom RANDOM = new SecureRandom();
    static String nonce() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    static boolean directAvailable(Context context) {
        try {
            ActivityInfo entry = context.getPackageManager().getActivityInfo(DIRECT_ENTRY, 0);
            return entry.enabled && entry.exported && entry.applicationInfo.enabled && entry.permission == null;
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
    }
    static void openDirect(Context context, String reference, LiveFrameCapturePolicy capture, LiveFrameTarget target) {
        if (!directAvailable(context) || ReferenceStore.get(context, reference) == null)
            throw new IllegalStateException("target_unavailable");
        Uri image = ReferenceStore.uri(reference);
        AdmissionPolicy.Entry admission = LiveAdmissionStore.issue(context, reference, capture.source(), target);
        Intent intent = new Intent(LiveTryOnContract.ACTION_TRY_ON).setComponent(DIRECT_ENTRY).setType("image/jpeg")
            .putExtra(LiveTryOnContract.REFERENCE_URI, image.toString())
            .putExtra(LiveTryOnContract.ADMISSION_TOKEN, admission.token())
            .putExtra(LiveTryOnContract.REQUEST_ID, admission.requestId())
            .putExtra(LiveTryOnContract.EXPIRES_AT_ELAPSED_MS, admission.expiresAt())
            .putExtra(LiveTryOnContract.CATEGORY, admission.category())
            .putExtra(LiveTryOnContract.PROMPT, admission.prompt())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("LIVE outfit reference", image));
        try {
            context.grantUriPermission(PACKAGE, image, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (RuntimeException unavailable) {
            LiveAdmissionStore.discard(admission.token());
            try { context.revokeUriPermission(PACKAGE, image, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException ignored) { }
            throw unavailable;
        }
    }
    private LiveTryOnHandoff() {}
}
