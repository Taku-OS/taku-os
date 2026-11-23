// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import org.taku.tryon.contract.LiveTryOnContract;

/** Admission and reference have separate lifetimes; public Agent calls can never read the admission. */
final class LiveAdmissionStore {
    private static final AdmissionPolicy POLICY = new AdmissionPolicy();
    static AdmissionPolicy.Entry issue(Context context, String reference, LivePage room, LiveFrameTarget target) {
        try {
            PackageInfo app = context.getPackageManager().getPackageInfo(LiveTryOnContract.TARGET_PACKAGE,
                PackageManager.GET_SIGNING_CERTIFICATES);
            if (app.applicationInfo == null) throw new IllegalStateException("target_unavailable");
            long now = SystemClock.elapsedRealtime();
            AdmissionPolicy.Entry entry = new AdmissionPolicy.Entry(LiveTryOnHandoff.nonce(), UUID.randomUUID().toString(),
                reference, room.packageName(), room.windowId(), room.hostKey(), app.applicationInfo.uid, signer(app),
                "upper_body", target.outfitPrompt(), now, now + LiveTryOnContract.ADMISSION_TTL_MS);
            POLICY.issue(entry, now); return entry;
        } catch (Exception invalid) { throw new IllegalStateException("target_unavailable"); }
    }
    static Bundle redeem(Context context, int callerUid, Bundle request) {
        try {
            if (request == null || request.size() != 2 || !(request.get(LiveTryOnContract.ADMISSION_TOKEN) instanceof String)
                    || !(request.get(LiveTryOnContract.REQUEST_ID) instanceof String)) return denied();
            PackageInfo app = context.getPackageManager().getPackageInfo(LiveTryOnContract.TARGET_PACKAGE,
                PackageManager.GET_SIGNING_CERTIFICATES);
            if (app.applicationInfo == null || callerUid != app.applicationInfo.uid) return denied();
            AdmissionPolicy.Entry entry = POLICY.redeem(request.getString(LiveTryOnContract.ADMISSION_TOKEN),
                request.getString(LiveTryOnContract.REQUEST_ID), callerUid, signer(app), SystemClock.elapsedRealtime());
            if (entry == null || ReferenceStore.get(context, entry.reference()) == null) return denied();
            Bundle result = ShoppingObserverService.reply(true, "redeemed");
            result.putString(LiveTryOnContract.REFERENCE_URI, ReferenceStore.uri(entry.reference()).toString());
            result.putString(LiveTryOnContract.CATEGORY, entry.category()); result.putString(LiveTryOnContract.PROMPT, entry.prompt());
            result.putString(LiveTryOnContract.REQUEST_ID, entry.requestId());
            result.putLong(LiveTryOnContract.EXPIRES_AT_ELAPSED_MS, entry.expiresAt());
            return result;
        } catch (Exception unavailable) { return denied(); }
    }
    static void discard(String token) { POLICY.discard(token); }
    private static Bundle denied() { return ShoppingObserverService.reply(false, "invalid_admission"); }
    private static String signer(PackageInfo app) throws Exception {
        if (app.signingInfo == null) throw new SecurityException("missing_signer");
        var certs = app.signingInfo.getApkContentsSigners();
        if (certs == null || certs.length == 0) throw new SecurityException("missing_signer");
        var encoded = new ArrayList<String>();
        for (var cert : certs) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray());
            StringBuilder value = new StringBuilder(); for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            encoded.add(value.toString());
        }
        Collections.sort(encoded); return String.join(":", encoded);
    }
    private LiveAdmissionStore() {}
}
