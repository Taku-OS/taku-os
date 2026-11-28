package org.agentos.lab;

import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONArray;
import org.json.JSONObject;

final class NotebookSocialSourceRequest {
    private static final String NOTEBOOK = "org.agentos.notebook";
    private static final Set<String> EVIDENCE_KEYS = Set.of(
            "handle", "author", "postedMinute", "translation", "kind", "snippets", "anchors", "early", "accountRead");

    private NotebookSocialSourceRequest() {}

    static JSONObject resolve(JSONObject request) throws Exception {
        if (!NOTEBOOK.equals(request.getString("consumer"))) throw new SecurityException("Notebook source access denied");
        String packageName = value(request, "sourcePackage", 160);
        NotebookSocialSource platform = NotebookSocialSource.byPackage(packageName);
        if (platform == null) throw invalidEvidence();
        JSONObject input = request.getJSONObject("evidence");
        for (var keys = input.keys(); keys.hasNext();) {
            if (!EVIDENCE_KEYS.contains(keys.next())) throw invalidEvidence();
        }
        boolean x = platform == NotebookSocialSource.X;
        String handle = value(input, "handle", x ? 16 : 100);
        String author = x ? "" : value(input, "author", 120);
        String posted = value(input, "postedMinute", 16);
        String language = value(input, "translation", 24);
        List<String> snippets = strings(input, "snippets");
        List<String> anchors = strings(input, "anchors");
        boolean facebookReel = platform == NotebookSocialSource.FACEBOOK && "reel".equals(input.optString("kind"));
        var evidence = new NotebookSocialSourceLookup.Evidence(handle, author, posted, language, facebookReel, snippets, anchors);
        String screenshot = value(request, "screenshot", 300_000);
        if (!screenshot.startsWith("data:image/jpeg;base64,")) throw invalidEvidence();
        ZoneId zone = ZoneId.of(value(request, "sourceZone", 100));
        long capturedAt = request.getLong("capturedAt");
        if (capturedAt <= 0 || capturedAt > System.currentTimeMillis() + 60_000) throw invalidEvidence();
        var xEvidence = new NotebookXSourceMatch.Evidence(handle, posted, language, snippets, anchors);
        boolean publicEvidence = x ? NotebookXSourceMatch.canVerifyPublicPost(xEvidence, zone, capturedAt)
                : evidence.canSearch();

        JSONObject selected = SystemAi.resolve(NOTEBOOK, "image", request.optString("model"));
        long revision = selected.getLong("revision");
        String model = subscriptionRoute(selected.getString("id"));
        SystemAi.current(NOTEBOOK, revision);
        SystemAi.admit(NOTEBOOK);
        long started = SystemClock.elapsedRealtime();
        try {
            String publicLink = null;
            String navigationLink = null;
            Exception publicFailure = null;
            if (publicEvidence) {
                try {
                    if (x) {
                        publicLink = NotebookXSourceLookup.resolve(model, xEvidence, zone, capturedAt);
                        navigationLink = publicLink;
                    } else {
                        var search = NotebookSocialSourceLookup.resolve(model, platform, evidence);
                        publicLink = search.verified();
                        navigationLink = search.navigationLink();
                    }
                } catch (Exception unavailable) {
                    if (Thread.currentThread().isInterrupted()) throw unavailable;
                    publicFailure = unavailable;
                }
            }
            String nativeLink = null;
            if (publicLink == null || platform == NotebookSocialSource.REDNOTE) {
                try {
                    nativeLink = NotebookNativeSourceLookup.resolve(
                            model, revision, packageName, screenshot, evidence, navigationLink);
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    String error = failure.getMessage();
                    Log.i("NotebookNativeSource", platform.name() + " native lookup "
                            + (error != null && error.matches("NOTEBOOK_[A-Z_]{1,70}")
                                    ? error : failure.getClass().getSimpleName()));
                    if (publicLink == null) throw failure;
                }
            }
            if (nativeLink == null && publicLink == null && publicFailure != null) throw publicFailure;
            SystemAi.current(NOTEBOOK, revision);
            SystemAi.record(NOTEBOOK, model, started, "completed");
            if (nativeLink != null && (publicLink == null || publicLink.equals(nativeLink))) {
                return new JSONObject().put("url", nativeLink).put("via", "native_share");
            }
            if (publicLink != null) return new JSONObject().put("url", publicLink).put("via", "verified_web");
            return new JSONObject();
        } catch (Exception failure) {
            SystemAi.record(NOTEBOOK, model, started, "failed", SystemAi.errorCode(failure));
            throw failure;
        }
    }

    private static String subscriptionRoute(String id) throws Exception {
        if (id.startsWith("codex/")) return id;
        if (id.startsWith("group/")) {
            String raw = SystemAi.SETTINGS.get(MagpieSettings.GROUPS);
            JSONArray groups = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                if (!id.equals("group/" + group.optString("id"))) continue;
                JSONArray members = group.optJSONArray("members");
                if (members != null && members.length() > 0) {
                    for (int j = 0; j < members.length(); j++) {
                        if (!members.optString(j).startsWith("codex/")) {
                            throw new IOException("Notebook source lookup requires a Codex subscription route");
                        }
                    }
                    return id;
                }
            }
        }
        throw new IOException("Notebook source lookup requires a Codex subscription route");
    }

    private static List<String> strings(JSONObject input, String key) throws Exception {
        JSONArray values = input.getJSONArray(key);
        if (values.length() > 5) throw invalidEvidence();
        var result = new ArrayList<String>();
        for (int i = 0; i < values.length(); i++) {
            if (!(values.get(i) instanceof String text) || text.length() > 180) throw invalidEvidence();
            if (!text.isBlank()) result.add(text.strip());
        }
        return List.copyOf(result);
    }

    private static String value(JSONObject input, String key, int max) throws Exception {
        if (!(input.get(key) instanceof String text) || text.length() > max) throw invalidEvidence();
        return text.strip();
    }

    private static IOException invalidEvidence() {
        return new IOException("Invalid Notebook social source evidence");
    }
}
