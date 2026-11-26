package org.agentos.lab;

import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fake public embed responses only: no account, network or Android UI. */
public final class NotebookXPostEvidenceTest {
    public static void main(String[] args) throws Exception {
        var evidence = new JSONObject().put("handle", "example").put("postedMinute", "2026-01-02T12:30")
                .put("snippets", new JSONArray().put("Upload files using a basketball hoop"));
        var candidates = new JSONArray().put("https://x.com/example/status/123");
        long captured = Instant.parse("2026-01-02T12:31:00Z").toEpochMilli();
        NotebookXPostEvidence.Fetcher exact = (id, deadline) -> post(id, "example", "2026-01-02T12:30:15Z");
        check("https://x.com/example/status/123".equals(NotebookXPostEvidence.verify(evidence, candidates, captured, "UTC", exact)));
        check(NotebookXPostEvidence.verify(evidence, candidates, captured, "UTC", (id, deadline) -> post(id, "other", "2026-01-02T12:30:15Z")).isEmpty());
        check(NotebookXPostEvidence.verify(evidence, candidates, captured, "UTC", (id, deadline) -> post(id, "example", "2026-01-02T12:29:15Z")).isEmpty());
        check(NotebookXPostEvidence.verify(evidence, candidates, captured, "UTC", (id, deadline) -> post(id, "example", "2026-01-02T12:32:15Z")).isEmpty());
        check(NotebookXPostEvidence.verify(evidence, new JSONArray().put("https://x.com.evil/example/status/123"), captured, "UTC", exact).isEmpty());
        check(NotebookXPostEvidence.verify(evidence, new JSONArray().put("https://x.com/example/status/123").put("https://x.com/example/status/456"), captured, "UTC", exact).isEmpty());
        System.out.println("NotebookXPostEvidenceTest: exact author/time/text and ambiguity rejection passed");
    }
    private static JSONObject post(String id, String author, String time) {
        return new JSONObject().put("id_str", id).put("user", new JSONObject().put("screen_name", author))
                .put("created_at", time).put("text", "Upload files using a basketball hoop");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
