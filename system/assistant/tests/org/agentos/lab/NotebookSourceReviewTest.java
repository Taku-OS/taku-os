package org.agentos.lab;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NotebookSourceReviewTest {
    public static void main(String[] args) throws Exception {
        verifiesRemainingCandidates();
        comparesCanonicalLinks();
        recognizesFacebookControls();
        System.out.println("Notebook source review tests passed");
    }

    private static void verifiesRemainingCandidates() throws Exception {
        var evidence = new NotebookSocialSourceLookup.Evidence("@mai", "", "", "", false,
                List.of("night markets near the old station"), List.of());
        var match = new NotebookPublicPost.Published("https://www.instagram.com/p/Port123/",
                List.of("mai"), evidence.snippets());
        var otherMatch = new NotebookPublicPost.Published("https://www.instagram.com/p/Port456/",
                match.authors(), match.text());
        var failed = CompletableFuture.<NotebookPublicPost.Published>failedFuture(new IOException("HTTP 429"));
        var cancelled = new CompletableFuture<NotebookPublicPost.Published>();
        cancelled.cancel(false);
        var verified = CompletableFuture.completedFuture(match);
        check(match.url().equals(NotebookSocialSourceLookup.verifiedCandidate(
                List.of(failed, verified, cancelled), evidence)));
        check(NotebookSocialSourceLookup.verifiedCandidate(List.of(verified, failed,
                CompletableFuture.completedFuture(otherMatch)), evidence) == null);
    }

    private static void comparesCanonicalLinks() throws Exception {
        String id = "1234567890abcdef12345678";
        String canonical = "https://www.xiaohongshu.com/explore/" + id;
        String rednote = "https://www.rednote.com/zh/discovery/item/" + id;
        var equivalent = NotebookSocialSourceRequest.sourceResult(NotebookSocialSource.REDNOTE, rednote, canonical);
        check(equivalent.getString("via").equals("native_share"));
        check(equivalent.getString("url").equals(canonical));
        var different = NotebookSocialSourceRequest.sourceResult(NotebookSocialSource.REDNOTE, rednote,
                "https://www.xiaohongshu.com/explore/abcdefabcdefabcdefabcdef");
        check(different.getString("via").equals("verified_web"));
        check(different.getString("url").equals(canonical));
        check(NotebookSocialSourceRequest.sourceResult(NotebookSocialSource.REDNOTE, null, "invalid").length() == 0);
        check(NotebookSocialSourceRequest.sourceResult(NotebookSocialSource.REDNOTE, null, rednote)
                .getString("url").equals(canonical));
    }

    private static void recognizesFacebookControls() throws Exception {
        check(NotebookSocialSource.isFacebookReadRow("Reels search results, Item 2 of 5"));
        check(NotebookSocialSource.isFacebookReadRow("Posts, Item 1 of 3"));
        check(NotebookSocialSource.isFacebookReadRow("Reels个搜索结果，第2项，共5项"));
        check(NotebookSocialSource.isFacebookReadRow("Mai, 1.2K followers"));
        check(!NotebookSocialSource.isFacebookReadRow("Follow Mai, item 1 of 3"));
        for (String label : List.of("Mai, Verified, 1.2K followers", "Mai, 1 follower", "Mai，12位粉丝")) {
            var view = new NotebookNativeSourceLookup.View("", new JSONArray().put(control(label)), false);
            check(view.facebookAuthor("Mai") == 0);
            check(view.facebookAuthor("Kai") == -1);
        }
        for (String label : List.of("Reels search results, Item 1 of 3", "Reels, search results", "Reels个搜索结果，第1项，共3项")) {
            var view = new NotebookNativeSourceLookup.View("", new JSONArray().put(control(label)), false);
            check(view.facebookReelsSearchFilter() == 0);
        }
    }

    private static JSONObject control(String label) throws Exception {
        return new JSONObject().put("id", 0).put("role", "navigate").put("label", label);
    }

    private static void check(boolean passed) {
        if (!passed) throw new AssertionError();
    }
}
