package org.agentos.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;

/** Quiet work-log row. Command, status and expanded output all come from the same native item. */
final class NativeActivityView extends LinearLayout {
    private final DrawerTheme theme;
    private final ConversationExpansion expansion;
    private final NativeToolPresentation.Labels labels;
    private final LinearLayout header;
    private final TextView title, status, detail;
    private final NativeMarkdownView markdown;
    private final ImageView glyph, arrow;
    private AgentConversation.Entry entry;
    private String label = "", state = "", detailText;

    NativeActivityView(Context context, DrawerTheme theme, ConversationExpansion expansion) {
        super(context);
        this.theme = theme;
        this.expansion = expansion;
        labels = labels(context);
        AgentSettingsUi ui = new AgentSettingsUi(context, theme);
        setOrientation(VERTICAL);
        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(48));
        header.setPadding(0, ui.dp(4), 0, ui.dp(4));
        header.setFocusable(true);
        glyph = new ImageView(context);
        glyph.setColorFilter(theme.muted);
        glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams mark = new LayoutParams(ui.dp(16), ui.dp(16));
        mark.rightMargin = ui.dp(8);
        header.addView(glyph, mark);
        title = ui.text("", 14, false);
        ChatDesign.text(title, ChatDesign.Role.META);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LayoutParams(0, -2, 1));
        status = ui.text("", 12, false);
        ChatDesign.text(status, ChatDesign.Role.CAPTION);
        status.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        header.addView(status);
        arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_drawer_chevron_down);
        arrow.setColorFilter(theme.muted);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(arrow, new LayoutParams(ui.dp(14), ui.dp(14)));
        addView(header);
        detail = ui.text("", 14, false);
        ChatDesign.text(detail, ChatDesign.Role.META);
        detail.setTextIsSelectable(true);
        detail.setPaddingRelative(ui.dp(24), 0, 0, ui.dp(8));
        addView(detail);
        markdown = new NativeMarkdownView(context, theme, true);
        markdown.setPaddingRelative(ui.dp(24), 0, 0, ui.dp(8));
        addView(markdown);
        header.setOnClickListener(view -> {
            if (entry == null) return;
            expansion.toggle("item/" + entry.key());
            updateExpansion();
        });
    }

    void bind(AgentConversation.Entry entry, JSONObject outputs) {
        this.entry = entry;
        boolean tool = entry.kind == AgentConversation.Kind.TOOL;
        label = tool ? NativeToolPresentation.title(entry.item, labels) : getContext().getString(R.string.chat_commentary);
        state = tool ? NativeToolPresentation.status(entry.item, labels) : "";
        String preview = tool && entry.type.equals("commandExecution") ? entry.item.optString("command") : "";
        title.setText(tool ? label + (preview.isEmpty() ? "" : " · " + preview) : entry.text);
        ChatDesign.text(title, ChatDesign.Role.META);
        status.setText(state);
        status.setTextColor(tool && NativeToolPresentation.status(entry.item).equals("Failed")
                ? ChatDesign.color(getContext(), R.color.taku_status_error) : theme.muted);
        ChatDesign.text(status, ChatDesign.Role.CAPTION);
        glyph.setImageResource(!tool ? R.drawable.ic_chat_bubble_outline
                : entry.type.equals("commandExecution") ? R.drawable.ic_code
                : entry.type.equals("fileChange") ? R.drawable.ic_copy
                : entry.type.equals("webSearch") ? R.drawable.ic_language : R.drawable.ic_home_apps);
        String value = tool ? NativeToolPresentation.details(entry.item, outputs == null ? "" : outputs.optString(entry.id), labels)
                : NativeTurnPresentation.visibleText(entry.text, true);
        if (value.isEmpty()) value = getContext().getString(R.string.chat_no_output);
        if (!value.equals(detailText)) {
            detailText = value;
            if (tool) {
                detail.setText(value);
                ChatDesign.text(detail, ChatDesign.Role.META);
                detail.setTypeface(Typeface.MONOSPACE);
            } else markdown.bind(value);
        }
        updateExpansion();
    }

    private void updateExpansion() {
        boolean open = expansion.contains("item/" + entry.key());
        detail.setVisibility(open && entry.kind == AgentConversation.Kind.TOOL ? VISIBLE : GONE);
        markdown.setVisibility(open && entry.kind != AgentConversation.Kind.TOOL ? VISIBLE : GONE);
        arrow.setRotation(open ? 0 : -90);
        String kind = entry.kind == AgentConversation.Kind.TOOL ? label : getContext().getString(R.string.chat_commentary);
        header.setContentDescription(getContext().getString(open
                ? R.string.chat_collapse_item : R.string.chat_expand_item,
                kind + (state.isEmpty() ? "" : " · " + state)));
    }

    /** Android resources adapt the pure native-record presenter without touching returned content. */
    static NativeToolPresentation.Labels labels(Context context) {
        return (key, arguments) -> {
            int resource;
            switch (key) {
                case "device_info": resource = R.string.chat_tool_device_info; break;
                case "observe_background": resource = R.string.chat_tool_observe_background; break;
                case "open_app": resource = R.string.chat_tool_open_app; break;
                case "tap": resource = R.string.chat_tool_tap; break;
                case "type_text": resource = R.string.chat_tool_type_text; break;
                case "command": resource = R.string.chat_tool_command; break;
                case "file_change": resource = R.string.chat_tool_file_change; break;
                case "extension": resource = R.string.chat_tool_extension; break;
                case "web_search": resource = R.string.chat_tool_web_search; break;
                case "compaction": resource = R.string.chat_tool_compaction; break;
                case "collaboration": resource = R.string.chat_tool_collaboration; break;
                case "review_enter": resource = R.string.chat_tool_review_enter; break;
                case "review_exit": resource = R.string.chat_tool_review_exit; break;
                case "working": resource = R.string.chat_tool_working; break;
                case "completed": resource = R.string.chat_tool_completed; break;
                case "failed": resource = R.string.chat_tool_failed; break;
                case "declined": resource = R.string.chat_tool_declined; break;
                case "interrupted": resource = R.string.chat_tool_interrupted; break;
                case "cancelled": resource = R.string.chat_tool_cancelled; break;
                case "pending": resource = R.string.chat_tool_pending; break;
                case "image_result": resource = R.string.chat_tool_image_result; break;
                case "exit_code": resource = R.string.chat_tool_exit_code; break;
                case "output_tail": resource = R.string.chat_tool_output_tail; break;
                default: resource = R.string.chat_tool_call;
            }
            return context.getString(resource, arguments);
        };
    }
}
