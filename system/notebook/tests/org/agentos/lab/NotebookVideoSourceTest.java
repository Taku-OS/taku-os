package org.agentos.lab;

import java.io.IOException;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NotebookVideoSourceTest {
    private static final String POST = "1234567890123456789";
    private static final String SOURCE = "https://x.com/example/status/" + POST;
    private static final String SAVED = "a".repeat(64);

    public static void main(String[] args) throws Exception {
        check(POST.equals(NotebookVideoSource.postId(SOURCE + "?s=20")));
        check(POST.equals(NotebookVideoSource.postId("https://twitter.com/i/web/status/" + POST + "/video/1")));
        rejects("VIDEO_SOURCE_UNSUPPORTED", () -> NotebookVideoSource.postId("https://x.com.other/status/" + POST));
        rejects("VIDEO_SOURCE_UNSUPPORTED", () -> NotebookVideoSource.postId("https://example@x.com/a/status/" + POST));
        rejects("VIDEO_MEDIA_URL_UNSUPPORTED", () -> NotebookVideoSource.checkedUrl("https://video.twimg.com.evil/any", false));
        rejects("VIDEO_MEDIA_URL_UNSUPPORTED", () -> NotebookVideoSource.checkedUrl("https://video.twimg.com:123/any", false));

        var saved = new JSONObject().put("id", SAVED).put("sha256", "b".repeat(64))
                .put("sourceUrl", SOURCE).put("sourceResolution", "linked");
        check(SOURCE.equals(NotebookVideoSource.requireLinkedSource(saved, SAVED)));
        rejects("VIDEO_REFERENCE_INVALID", () -> NotebookVideoSource.requireLinkedSource(saved, "c".repeat(64)));
        saved.put("sourceResolution", "pending");
        rejects("VIDEO_SOURCE_NOT_LINKED", () -> NotebookVideoSource.requireLinkedSource(saved, SAVED));

        var compact = new JSONObject().put("type", "video/mp4")
                .put("src", "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/480x480/video.mp4");
        var hd720 = new JSONObject().put("type", "video/mp4")
                .put("src", "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1280x720/video.mp4");
        var hd1080 = new JSONObject().put("type", "video/mp4")
                .put("src", "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1920x1080/video.mp4");
        var video = new JSONObject().put("durationMs", 2400)
                .put("mediaAvailability", new JSONObject().put("status", "available"))
                .put("variants", new JSONArray().put(hd1080).put(compact).put(hd720));
        var post = new JSONObject().put("id_str", POST).put("video", video);
        // Prefer compact <=800 px MP4 when available.
        check(compact.getString("src").equals(NotebookVideoSource.select(post, POST).getString("mediaUrl")));
        // Fall back to the smallest valid HD MP4 when no <=800 px variant is published.
        video.put("variants", new JSONArray().put(hd1080).put(hd720));
        check(hd720.getString("src").equals(NotebookVideoSource.select(post, POST).getString("mediaUrl")));
        rejects("VIDEO_POST_ID_MISMATCH", () -> NotebookVideoSource.select(post, "99"));
        post.put("user", new JSONObject().put("protected", true));
        rejects("VIDEO_NOT_PUBLIC", () -> NotebookVideoSource.select(post, POST));
        post.remove("user");
        video.getJSONObject("mediaAvailability").put("status", "unavailable");
        rejects("VIDEO_UNAVAILABLE", () -> NotebookVideoSource.select(post, POST));

        check(Arrays.equals(NotebookVideoSource.samples(2400), new long[]{0, 500, 1000, 1500, 2000}));
        check(NotebookVideoSource.samples(60_000).length == 32);
        // Reference output of the public widget calculation for synthetic IDs.
        check("bhi2ay3f28n".equals(NotebookVideoSource.publicEmbedToken("1")));
        check("2zqic77uqyk".equals(NotebookVideoSource.publicEmbedToken(POST)));
        check("6qen9jwl6qt".equals(NotebookVideoSource.publicEmbedToken("99999999999999999999")));
        System.out.println("NotebookVideoSourceTest: exact source, public media, HD fallback, bounds and embed calculation passed");
    }

    private static void check(boolean passed) {
        if (!passed) throw new AssertionError("Video source invariant failed");
    }

    private static void rejects(String code, Checked operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected " + code);
        } catch (IOException rejected) {
            if (!code.equals(rejected.getMessage())) throw rejected;
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
