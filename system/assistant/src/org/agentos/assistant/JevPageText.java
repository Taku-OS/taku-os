package org.agentos.assistant;

import android.app.assist.AssistStructure;
import android.text.InputType;

/** Bounded text from Android's current assist capture; never another app's history. */
final class JevPageText {
    static String read(AssistStructure structure) {
        if (structure == null) return "";
        StringBuilder out = new StringBuilder(); int[] nodes = {0};
        for (int i = 0; i < structure.getWindowNodeCount() && nodes[0] < 500; i++)
            visit(structure.getWindowNodeAt(i).getRootViewNode(), out, nodes, 0);
        return out.toString().trim();
    }
    private static void visit(AssistStructure.ViewNode node, StringBuilder out, int[] count, int depth) {
        if (node == null || depth > 32 || ++count[0] > 500 || out.length() >= 12000 || node.isAssistBlocked()) return;
        int type = node.getInputType(), variation = type & InputType.TYPE_MASK_VARIATION;
        if ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return;
        if ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return;
        append(out, node.getText());
        if (node.getText() == null) append(out, node.getContentDescription());
        for (int i = 0; i < node.getChildCount() && count[0] < 500; i++) visit(node.getChildAt(i), out, count, depth + 1);
    }
    private static void append(StringBuilder out, CharSequence value) {
        if (value == null || out.length() >= 12000) return;
        String text = value.toString().trim(); if (text.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(text, 0, Math.min(text.length(), 12000 - out.length()));
    }
}
