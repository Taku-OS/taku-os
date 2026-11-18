package org.agentos.assistant;

import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import org.commonmark.Extension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.*;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.*;
import java.util.*;

/** CommonMark + GFM AST. Parsing never edits code literals or interprets HTML as UI. */
final class NativeMarkdown {
    private static final List<Extension> EXTENSIONS = Arrays.asList(TablesExtension.create(),
            StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer SIGNATURE = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    static String speechText(String source) {
        StringBuilder out=new StringBuilder();
        parse(source).accept(new AbstractVisitor(){
            public void visit(Text node){out.append(node.getLiteral());}
            public void visit(Code node){out.append(node.getLiteral());}
            public void visit(SoftLineBreak node){out.append(' ');}
            public void visit(HardLineBreak node){out.append('\n');}
            public void visit(Paragraph node){visitChildren(node);out.append('\n');}
            public void visit(Heading node){visitChildren(node);out.append('\n');}
            public void visit(FencedCodeBlock node){}
            public void visit(IndentedCodeBlock node){}
            public void visit(HtmlBlock node){}
            public void visit(HtmlInline node){}
            public void visit(Image node){}
            public void visit(ThematicBreak node){}
            public void visit(LinkReferenceDefinition node){}
        });
        return out.toString().trim();
    }

    static Node parse(String source) { return PARSER.parse(source == null ? "" : source); }
    static String signature(Node node) { return SIGNATURE.render(node); }

    static CharSequence render(String source, int codeBackground) {
        return render(parse(source), codeBackground, 12);
    }

    static SpannableStringBuilder render(Node node, int codeBackground, int indent) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        append(node, out, codeBackground, indent, 0);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    /** The native block view supplies the semantic heading role, so do not scale it twice. */
    static SpannableStringBuilder renderHeading(Node node, int codeBackground, int indent) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        children(node, out, codeBackground, indent, 0);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static void append(Node node, SpannableStringBuilder out, int background, int indent, int depth) {
        int start = out.length();
        if (node instanceof Text) out.append(((Text) node).getLiteral());
        else if (node instanceof SoftLineBreak) out.append('\n');
        else if (node instanceof HardLineBreak) out.append('\n');
        else if (node instanceof Code) {
            out.append(((Code) node).getLiteral());
            span(out, new TypefaceSpan("monospace"), start);
            span(out, new BackgroundColorSpan(background), start);
            span(out, new RelativeSizeSpan(.9f), start);
        } else if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            out.append(node instanceof FencedCodeBlock ? ((FencedCodeBlock) node).getLiteral()
                    : ((IndentedCodeBlock) node).getLiteral());
            span(out, new TypefaceSpan("monospace"), start);
            span(out, new BackgroundColorSpan(background), start);
        } else if (node instanceof Image) {
            out.append("Image: "); children(node, out, background, indent, depth);
            if (out.length() == start + 3) out.append("View image");
            span(out, new NativeMarkdownLinks.Link(((Image) node).getDestination(), true), start);
        } else if (node instanceof HtmlInline) out.append(((HtmlInline) node).getLiteral());
        else if (node instanceof HtmlBlock) out.append(((HtmlBlock) node).getLiteral());
        else if (node instanceof LinkReferenceDefinition) return;
        else if (node instanceof ThematicBreak) out.append("────────");
        else if (node instanceof TaskListItemMarker) out.append(((TaskListItemMarker) node).isChecked() ? "☑ " : "☐ ");
        else if (node instanceof ListItem) {
            boolean task = containsTask(node);
            if (!task) {
                boolean ordered = node.getParent() instanceof OrderedList;
                int number = ordered ? ((OrderedList) node.getParent()).getMarkerStartNumber() : 0;
                if (ordered) for (Node prev = node.getPrevious(); prev != null; prev = prev.getPrevious()) number++;
                out.append(ordered ? number + ". " : "• ");
            }
            children(node, out, background, indent, depth + 1);
            span(out, new LeadingMarginSpan.Standard(indent * depth * 2, indent * (depth * 2 + 2)), start);
        } else {
            children(node, out, background, indent, depth);
            if (node instanceof StrongEmphasis) span(out, new MediumSpan(), start);
            if (node instanceof Emphasis) span(out, hasHan(out, start)
                    ? new MediumSpan() : new StyleSpan(Typeface.ITALIC), start);
            if (node instanceof Strikethrough) span(out, new StrikethroughSpan(), start);
            if (node instanceof Link) span(out, new NativeMarkdownLinks.Link(((Link) node).getDestination(), false), start);
            if (node instanceof Heading) {
                span(out, new MediumSpan(), start);
                span(out, new RelativeSizeSpan(((Heading) node).getLevel() <= 2 ? 1.18f : 1.06f), start);
            }
            if (node instanceof BlockQuote) span(out, new QuoteSpan(0xff8c857c, Math.max(2, indent / 5), indent), start);
        }
    }

    /** Taku's emphasis is real medium weight; never synthesize 700-weight or italic Chinese. */
    private static final class MediumSpan extends MetricAffectingSpan {
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) {
            paint.setTypeface(Typeface.create(paint.getTypeface(), 500, false));
            paint.setFakeBoldText(false);
        }
    }

    private static boolean hasHan(CharSequence value, int start) {
        for (int index = start; index < value.length();) {
            int codePoint = Character.codePointAt(value, index);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsTask(Node item) {
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TaskListItemMarker) return true;
            if (child instanceof Paragraph && containsTask(child)) return true;
        }
        return false;
    }

    private static void children(Node node, SpannableStringBuilder out, int background, int indent, int depth) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof LinkReferenceDefinition) continue;
            if (child instanceof Block && out.length() > 0 && child.getPrevious() instanceof Block
                    && out.charAt(out.length() - 1) != '\n') out.append('\n');
            append(child, out, background, indent, depth);
            if (child instanceof TableCell && child.getNext() != null) out.append("  |  ");
        }
    }

    private static void span(SpannableStringBuilder out, Object span, int start) {
        if (out.length() > start) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static String language(Node node) {
        if (!(node instanceof FencedCodeBlock)) return "text";
        String info = ((FencedCodeBlock) node).getInfo();
        return info == null || info.isBlank() ? "text" : info.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    }

    static String code(Node node) {
        return node instanceof FencedCodeBlock ? ((FencedCodeBlock) node).getLiteral() : ((IndentedCodeBlock) node).getLiteral();
    }
}
