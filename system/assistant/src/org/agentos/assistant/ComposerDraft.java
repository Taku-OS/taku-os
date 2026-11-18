package org.agentos.assistant;

/** Immutable identity of the editor content handed to an asynchronous operation. */
final class ComposerDraft {
    final String thread, revision, text, attachments;

    ComposerDraft(String thread, String revision, String text, String attachments) {
        this.thread = thread;
        this.revision = revision;
        this.text = text;
        this.attachments = attachments;
    }

    boolean matches(ComposerDraft current) {
        return thread.equals(current.thread) && revision.equals(current.revision)
                && text.equals(current.text) && attachments.equals(current.attachments);
    }

    boolean isEmpty() {
        return text.trim().isEmpty() && attachments.equals("[]");
    }

    boolean canRestoreInto(ComposerDraft current) {
        return thread.equals(current.thread) && current.isEmpty();
    }
}
