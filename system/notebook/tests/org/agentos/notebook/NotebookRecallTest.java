package org.agentos.notebook;

import java.util.Set;
import org.agentos.ai.NotebookSocialSource;

/** Pure recall policy fixtures; the production catalogue/SQLite is not replaced in the APK. */
public final class NotebookRecallTest {
    public static void main(String[] args) {
        var visual = new NotebookStore.Entry("Upload files", "Basketball upload animation", "https://x.com/example/status/123", false);
        var generic = new NotebookStore.Entry("Screenshot", "", "https://twitter.com/i/web/status/123", false);
        var different = new NotebookStore.Entry("Screenshot", "", "https://x.com/example/status/456", false);
        var edited = new NotebookStore.Entry("My reference", "", generic.sourceUrl, true);
        check(NotebookRecall.hasMatchingTopic(visual, Set.of("basketball")));
        check(!NotebookRecall.hasMatchingTopic(visual, Set.of("unrelated")));
        var matching = Set.of(NotebookRecall.postKey(visual));
        check(NotebookRecall.hasMatchingSource(generic, matching));
        check(!NotebookRecall.hasMatchingSource(different, matching));
        check(!NotebookRecall.hasMatchingSource(edited, matching));
        check(!NotebookRecall.hasMatchingSource(visual, matching));
        check("https://x.com/example/status/123".equals(NotebookSocialSource.X.canonicalUrl("https://mobile.twitter.com/Example/status/123?s=20")));
        check(NotebookSocialSource.X.canonicalUrl("https://x.com.evil/example/status/123") == null);
        check(NotebookSocialSource.X.canonicalUrl("https://user@x.com/example/status/123") == null);
        System.out.println("NotebookRecallTest: topics, exact posts and edited titles passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}

// The recall policy depends on these immutable catalogue fields; Android store tests are pending.
final class NotebookStore {
    static final class Entry {
        final String title, summary, sourceUrl;
        final boolean titleEdited;
        Entry(String title, String summary, String sourceUrl, boolean titleEdited) {
            this.title=title; this.summary=summary; this.sourceUrl=sourceUrl; this.titleEdited=titleEdited;
        }
    }
}
