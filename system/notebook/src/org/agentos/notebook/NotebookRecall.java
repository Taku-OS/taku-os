package org.agentos.notebook;

import java.util.Locale;
import java.util.Set;
import org.agentos.ai.NotebookSocialSource;

final class NotebookRecall {
    private NotebookRecall() {}

    static boolean hasMatchingTopic(NotebookStore.Entry entry, Set<String> phrases) {
        if (!hasTopic(entry)) return false;
        String title = entry.title.toLowerCase(Locale.ROOT);
        String summary = entry.summary.toLowerCase(Locale.ROOT);
        return phrases.stream().anyMatch(phrase -> title.contains(phrase) || summary.contains(phrase));
    }

    static boolean hasMatchingSource(NotebookStore.Entry entry, Set<String> matchingPosts) {
        if (entry.titleEdited || hasTopic(entry) || matchingPosts.isEmpty()) return false;
        String key = postKey(entry);
        return key != null && matchingPosts.contains(key);
    }

    private static boolean hasTopic(NotebookStore.Entry entry) {
        return !entry.summary.isBlank() || !("Screenshot".equals(entry.title) || entry.title.startsWith("Post by @"));
    }

    static String postKey(NotebookStore.Entry entry) {
        NotebookSocialSource platform = NotebookSocialSource.byUrl(entry.sourceUrl);
        if (platform == null) return null;
        String canonical = platform.canonicalUrl(entry.sourceUrl);
        // X post IDs are global, including for /i/web/status links and account renames.
        if (platform == NotebookSocialSource.X) {
            return platform.name() + ":" + canonical.substring(canonical.lastIndexOf('/') + 1);
        }
        return canonical;
    }
}
