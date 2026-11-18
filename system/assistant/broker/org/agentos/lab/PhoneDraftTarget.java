package org.agentos.lab;

import java.io.IOException;
import java.util.List;

/** Fail-closed target selection shared by the Android adapter and host tests. */
final class PhoneDraftTarget {
    static final int MAX_TEXT_LENGTH = 4000;
    static final int MAX_TITLE_LENGTH = 160;

    record Box(int left, int top, int right, int bottom) {
        boolean valid() { return right > left && bottom > top; }
        int height() { return bottom - top; }
        boolean contains(Box other) {
            return valid() && other.valid() && other.left >= left && other.top >= top
                    && other.right <= right && other.bottom <= bottom;
        }
    }

    record Window(int id, int displayId, int layer, boolean application,
                  boolean active, boolean focused, String packageName, Box bounds) {}

    record Node(int key, String packageName, Box bounds, boolean visible, boolean enabled,
                boolean editable, boolean password, boolean supportsSetText,
                boolean inScrollableContainer, String titleText, String draftText) {}

    record Selection(int titleKey, int editorKey, boolean alreadyPrepared) {}

    static void validateRequest(String packageName, String title, String text) throws IOException {
        if (packageName == null || packageName.length() > 200
                || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw failure("INVALID_PACKAGE_NAME");
        if (title == null || title.length() > MAX_TITLE_LENGTH || normalizeTitle(title).isEmpty())
            throw failure("INVALID_CONVERSATION_TITLE");
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH)
            throw failure("INVALID_DRAFT_TEXT");
        // Newlines are literal text in ACTION_SET_TEXT; other control characters are unnecessary.
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isISOControl(value) && value != '\n' && value != '\t')
                throw failure("INVALID_DRAFT_TEXT");
        }
    }

    static Window selectWindow(List<Window> windows, String expectedPackage) throws IOException {
        Window top = null;
        for (Window window : windows) {
            if (window.displayId() != 0 || !window.application()) continue;
            if (top == null || window.layer() > top.layer()) top = window;
        }
        if (top == null) throw failure("NO_FOREGROUND_WINDOW");
        int topCount = 0;
        for (Window window : windows)
            if (window.displayId() == 0 && window.application() && window.layer() == top.layer()) topCount++;
        if (topCount != 1) throw failure("AMBIGUOUS_FOREGROUND_WINDOW");
        if (!top.active() || !top.focused()) throw failure("FOREGROUND_WINDOW_NOT_ACTIVE");
        if (!expectedPackage.equals(top.packageName())) throw failure("FOREGROUND_PACKAGE_MISMATCH");
        if (!top.bounds().valid()) throw failure("FOREGROUND_BOUNDS_UNAVAILABLE");
        return top;
    }

    static Selection select(Window window, List<Node> nodes, String expectedPackage,
                            String expectedTitle, String requestedText) throws IOException {
        String title = normalizeTitle(expectedTitle);
        Node titleNode = null, editor = null;
        int titleCount = 0, editorCount = 0;
        for (Node node : nodes) {
            if (!node.visible()) continue;
            if (node.editable()) { editorCount++; editor = node; }
            if (isTitleCandidate(window.bounds(), node) && expectedPackage.equals(node.packageName())
                    && title.equals(normalizeTitle(node.titleText()))) {
                titleCount++;
                titleNode = node;
            }
        }
        if (titleCount == 0) throw failure("CONVERSATION_TITLE_NOT_VERIFIED");
        if (titleCount != 1) throw failure("AMBIGUOUS_CONVERSATION_TITLE");
        if (editorCount == 0) throw failure("NO_FOREGROUND_EDITOR");
        if (editorCount != 1) throw failure("AMBIGUOUS_FOREGROUND_EDITOR");
        if (!expectedPackage.equals(editor.packageName())) throw failure("EDITOR_PACKAGE_MISMATCH");
        if (editor.password()) throw failure("PASSWORD_EDITOR_REJECTED");
        if (!editor.enabled() || !editor.supportsSetText()) throw failure("EDITOR_NOT_WRITABLE");
        if (!window.bounds().contains(editor.bounds())
                || editor.bounds().top() < window.bounds().top() + window.bounds().height() / 2)
            throw failure("EDITOR_POSITION_NOT_VERIFIED");
        return new Selection(titleNode.key(), editor.key(), alreadyPrepared(editor.draftText(), requestedText));
    }

    static boolean alreadyPrepared(String draft, String requestedText) throws IOException {
        String existing = draft == null ? "" : draft;
        if (!existing.isEmpty() && !existing.equals(requestedText)) throw failure("EXISTING_DRAFT_PRESERVED");
        return existing.equals(requestedText);
    }

    static boolean isTitleCandidate(Box window, Node node) {
        // Message-list text must never count as the conversation header, even if the words match.
        return node.visible() && !node.editable() && !node.inScrollableContainer()
                && window.contains(node.bounds())
                && node.bounds().bottom() <= window.top() + window.height() / 4;
    }

    static String normalizeTitle(String title) {
        // Deliberately preserve punctuation, case, participant counts and all non-whitespace text.
        return title == null ? "" : title.replaceAll("[ \\t\\r\\n\\u00a0]+", " ").trim();
    }

    static IOException failure(String code) { return new IOException(code); }
}
