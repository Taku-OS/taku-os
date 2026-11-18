package org.agentos.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.widget.*;
import org.commonmark.node.*;
import org.commonmark.ext.gfm.tables.*;
import java.util.*;

/** Stable block views: completed paragraphs, selections and horizontal offsets survive streaming. */
final class NativeMarkdownView extends LinearLayout {
    private final DrawerTheme theme;
    private final AgentSettingsUi ui;
    private final boolean compact;
    private final ArrayList<BlockView> blocks = new ArrayList<>();
    private String source = "";

    NativeMarkdownView(Context context, DrawerTheme theme) {
        this(context, theme, false);
    }

    NativeMarkdownView(Context context, DrawerTheme theme, boolean compact) {
        super(context);
        this.theme = theme;
        this.compact = compact;
        ui = new AgentSettingsUi(context, theme);
        setOrientation(VERTICAL);
    }

    void bind(String value) {
        if (value.equals(source) && !blocks.isEmpty()) return;
        source = value;
        Node document = NativeMarkdown.parse(value);
        int index = 0;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof LinkReferenceDefinition) continue;
            String kind = node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock ? "code"
                    : node instanceof TableBlock ? "table" : standaloneImage(node) != null ? "image"
                    : node instanceof ThematicBreak ? "rule" : "text";
            BlockView block = index < blocks.size() ? blocks.get(index) : null;
            if (block == null || !block.kind.equals(kind)) {
                if (block != null) removeView(block.view);
                block = new BlockView(kind);
                if (index < blocks.size()) blocks.set(index, block); else blocks.add(block);
            }
            NativeConversationView.place(this, block.view, index, ui.dp(index == 0 ? 0 : node instanceof Heading ? 16 : 8));
            String signature = NativeMarkdown.signature(node);
            if (!signature.equals(block.signature)) {
                block.signature = signature;
                block.bind(node);
            }
            index++;
        }
        while (blocks.size() > index) removeView(blocks.remove(blocks.size() - 1).view);
    }

    private Image standaloneImage(Node node) {
        if (node instanceof Paragraph && node.getFirstChild() instanceof Image
                && node.getFirstChild().getNext() == null) return (Image) node.getFirstChild();
        return null;
    }

    private TextView text(ChatDesign.Role role) {
        TextView text = ui.text("", 16, true);
        ChatDesign.text(text, role);
        text.setTextIsSelectable(true);
        text.setLinkTextColor(theme.accent);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        return text;
    }

    private final class BlockView {
        final String kind;
        final View view;
        TextView body, language;
        String signature, code;
        BlockView(String kind) {
            this.kind = kind;
            if (kind.equals("text")) { view = body = text(compact ? ChatDesign.Role.META : ChatDesign.Role.BODY); }
            else if (kind.equals("code")) {
                LinearLayout card = ui.column();
                card.setBackground(theme.rounded(getContext(), theme.raised, 12, true));
                card.setClipToOutline(true);
                LinearLayout header = new LinearLayout(getContext());
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(ui.dp(12), 0, ui.dp(2), 0);
                language = ui.text("", 12, false);
                ChatDesign.text(language, ChatDesign.Role.CAPTION);
                header.addView(language, new LayoutParams(0, -2, 1));
                TextView copy = ui.text(getContext().getString(R.string.chat_copy), 14, false);
                ChatDesign.text(copy, ChatDesign.Role.META);
                copy.setGravity(Gravity.CENTER);
                copy.setContentDescription(getContext().getString(R.string.chat_copy_code));
                copy.setFocusable(true);
                copy.setOnClickListener(v -> {
                    NativeMarkdownLinks.copy(getContext(), code);
                    Toast.makeText(getContext(), R.string.chat_code_copied, Toast.LENGTH_SHORT).show();
                });
                header.addView(copy, new LayoutParams(-2, ui.dp(48)));
                copy.setMinWidth(ui.dp(48));
                copy.setPadding(ui.dp(8), 0, ui.dp(8), 0);
                card.addView(header);
                View line = new View(getContext()); line.setBackgroundColor(theme.border);
                card.addView(line, new LayoutParams(-1, ui.dp(1)));
                HorizontalScrollView scroll = horizontal();
                body = text(ChatDesign.Role.META);
                body.setTypeface(Typeface.MONOSPACE);
                body.setHorizontallyScrolling(true);
                body.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
                scroll.addView(body, new HorizontalScrollView.LayoutParams(-2, -2));
                card.addView(scroll, new LayoutParams(-1, -2));
                view = card;
            } else if (kind.equals("table")) {
                view = new NativeMarkdownTable(getContext(), theme);
            } else if (kind.equals("image")) view = new NativeMarkdownImage(getContext(), theme);
            else {
                LinearLayout holder = ui.column();
                holder.setPadding(0, ui.dp(6), 0, ui.dp(6));
                View rule = new View(getContext()); rule.setBackgroundColor(theme.border);
                holder.addView(rule, new LayoutParams(-1, ui.dp(1)));
                view = holder;
            }
        }

        void bind(Node node) {
            if (kind.equals("text")) {
                boolean heading = node instanceof Heading;
                body.setText(heading ? NativeMarkdown.renderHeading(node, theme.bubble, ui.dp(12))
                        : NativeMarkdown.render(node, theme.bubble, ui.dp(12)));
                ChatDesign.Role role = heading ? !compact && ((Heading) node).getLevel() <= 2
                        ? ChatDesign.Role.HEADING : ChatDesign.Role.LABEL
                        : compact ? ChatDesign.Role.META : ChatDesign.Role.BODY;
                ChatDesign.text(body, role);
            }
            else if (kind.equals("code")) {
                code = NativeMarkdown.code(node);
                String lang = NativeMarkdown.language(node);
                language.setText(lang);
                ChatDesign.text(language, ChatDesign.Role.CAPTION);
                body.setText(NativeCodeHighlight.render(code, lang, theme));
                ChatDesign.text(body, ChatDesign.Role.META);
                body.setTypeface(Typeface.MONOSPACE);
            } else if (kind.equals("table")) {
                ((NativeMarkdownTable) view).bind(node);
            } else if (kind.equals("image")) {
                Image image = standaloneImage(node);
                ((NativeMarkdownImage) view).bind(image.getDestination(), NativeMarkdown.render(image, 0, 0).toString());
            }
        }
    }

    private HorizontalScrollView horizontal() {
        HorizontalScrollView view = new HorizontalScrollView(getContext());
        view.setFillViewport(true);
        view.setHorizontalScrollBarEnabled(true);
        view.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        view.setFocusable(false);
        return view;
    }
}
