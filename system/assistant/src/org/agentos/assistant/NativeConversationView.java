package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** Keyed Android transcript. Updates the affected turn instead of rebuilding the whole feed. */
final class NativeConversationView extends LinearLayout {
    private final DrawerTheme theme;
    private final ConversationExpansion expansion;
    private final Map<String, View> rows = new LinkedHashMap<>();
    private final Map<String, String> userSignatures = new HashMap<>();
    private View welcome;
    private TextView historyNotice;
    private String historyNoticeText = "";

    NativeConversationView(Context context, DrawerTheme theme, SharedPreferences preferences, String thread) {
        super(context);
        this.theme = theme;
        expansion = new ConversationExpansion(preferences, thread);
        setOrientation(VERTICAL);
    }

    ConversationExpansion expansion() { return expansion; }

    void bind(AgentConversation conversation, JSONObject outputs) {
        List<NativeTurnPresentation.Block> blocks = conversation == null
                ? Collections.emptyList() : NativeTurnPresentation.blocks(conversation);
        int rowOffset = 0;
        String nextHistoryNotice = conversation == null ? "" : conversation.historyNotice;
        if (!nextHistoryNotice.isEmpty()) {
            if (historyNotice == null || !nextHistoryNotice.equals(historyNoticeText)) {
                if (historyNotice != null) removeView(historyNotice);
                historyNotice = new TextView(getContext());
                historyNotice.setTextSize(12);
                historyNotice.setTextColor(theme.muted);
                historyNotice.setPadding(theme.dp(getContext(), 12), theme.dp(getContext(), 9),
                        theme.dp(getContext(), 12), theme.dp(getContext(), 9));
                historyNotice.setBackground(theme.rounded(getContext(), theme.raised, 12, false));
                historyNotice.setText(nextHistoryNotice);
                historyNoticeText = nextHistoryNotice;
            }
            place(this, historyNotice, 0, theme.dp(getContext(), 6));
            rowOffset = 1;
        } else if (historyNotice != null) {
            removeView(historyNotice);
            historyNotice = null;
            historyNoticeText = "";
        }
        if (blocks.isEmpty()) {
            if (welcome == null) {
                AgentSettingsUi ui = new AgentSettingsUi(getContext(), theme);
                LinearLayout empty = ui.column();
                TextView heading = ui.text(getContext().getString(R.string.chat_welcome), 24, true);
                ChatDesign.text(heading, ChatDesign.Role.HEADING);
                ui.add(empty, heading, 24);
                welcome = empty;
            }
            place(this, welcome, rowOffset, theme.dp(getContext(), rowOffset == 0 ? 0 : 12));
        } else if (welcome != null) removeView(welcome);
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            NativeTurnPresentation.Block block = blocks.get(i);
            retained.add(block.key);
            View row = rows.get(block.key);
            if (block.user != null) {
                String signature = block.user.text + block.user.attachments.toString()
                        + block.user.workspaceObservations.toString();
                if (row == null || !signature.equals(userSignatures.get(block.key))) {
                    if (row != null) removeView(row);
                    row = userMessage(getContext(), theme, block.user.text, block.user.attachments,
                            block.user.workspaceObservations);
                    userSignatures.put(block.key, signature);
                }
            } else {
                if (row == null) row = new NativeTurnView(getContext(), theme, expansion);
                ((NativeTurnView) row).bind(block, outputs);
            }
            rows.put(block.key, row);
            row.setTag(block.key);
            place(this, row, i + rowOffset,
                    i == 0
                            ? (rowOffset == 0 ? 0 : theme.dp(getContext(), 6))
                            : ChatDesign.pixels(getContext(), R.dimen.taku_space_12));
        }
        Iterator<Map.Entry<String, View>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, View> item = iterator.next();
            if (!retained.contains(item.getKey())) {
                removeView(item.getValue());
                userSignatures.remove(item.getKey());
                iterator.remove();
            }
        }
    }

    static void place(LinearLayout parent, View child, int index, int topMargin) {
        if (parent.indexOfChild(child) != index) {
            if (child.getParent() == parent) parent.removeView(child);
            LayoutParams params = new LayoutParams(-1, -2);
            params.topMargin = topMargin;
            parent.addView(child, Math.min(index, parent.getChildCount()), params);
        } else {
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (params.topMargin != topMargin) { params.topMargin = topMargin; child.setLayoutParams(params); }
        }
    }

    View scrollAnchor(int scrollY) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (getTop() + child.getBottom() > scrollY) return child;
        }
        return null;
    }

    String anchorKey(View row) {
        if (row == null) return "";
        for (Map.Entry<String, View> entry : rows.entrySet())
            if (entry.getValue() == row) return entry.getKey();
        return "";
    }

    View rowForKey(String key) { return rows.get(key); }

    static View userMessage(Context context, DrawerTheme theme, String value, List<String> attachments) {
        return userMessage(context, theme, value, attachments, Collections.emptySet());
    }

    private static View userMessage(Context context, DrawerTheme theme, String value,
            List<String> attachments, Set<Integer> workspaceObservations) {
        AgentSettingsUi ui = new AgentSettingsUi(context, theme);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.END);
        // The bubble owns its inset; the transcript host owns the single screen gutter.
        // A weighted spacer caps it at 88% of the actual host width, including split windows.
        row.setWeightSum(1f);
        row.addView(new Space(context), new LayoutParams(0, 0, .12f));
        LinearLayout content = ui.column();
        content.setPadding(ChatDesign.pixels(context, R.dimen.taku_space_16),
                ChatDesign.pixels(context, R.dimen.taku_space_12),
                ChatDesign.pixels(context, R.dimen.taku_space_16),
                ChatDesign.pixels(context, R.dimen.taku_space_12));
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(theme.bubble);
        float large = ChatDesign.pixels(context, R.dimen.taku_radius_2xl);
        float small = ChatDesign.pixels(context, R.dimen.taku_radius_base);
        boolean rtl = context.getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        bubble.setCornerRadii(rtl
                ? new float[]{large, large, large, large, large, large, small, small}
                : new float[]{large, large, large, large, small, small, large, large});
        content.setBackground(bubble);
        if (!value.isEmpty()) {
            TextView text = ui.text(value, 16, true);
            ChatDesign.text(text, ChatDesign.Role.BODY);
            text.setTextIsSelectable(true);
            content.addView(text, new LayoutParams(-2, -2));
        }
        for (int i = 0; i < attachments.size(); i++) {
            boolean currentScreen = workspaceObservations.contains(i);
            String name = currentScreen ? context.getString(R.string.chat_current_screen) : attachments.get(i);
            TextView file = ui.text(name, 14, true);
            ChatDesign.text(file, ChatDesign.Role.META);
            file.setMaxLines(2);
            file.setEllipsize(android.text.TextUtils.TruncateAt.END);
            file.setContentDescription(context.getString(R.string.chat_sent_attachment, name));
            file.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            file.setBackground(theme.rounded(context, theme.raised, 8, false));
            android.graphics.drawable.Drawable glyph = context.getDrawable(currentScreen
                    ? R.drawable.ic_taku_image : R.drawable.ic_taku_document).mutate();
            glyph.setTint(theme.muted);
            glyph.setBounds(0, 0, ui.dp(16), ui.dp(16));
            file.setCompoundDrawables(glyph, null, null, null);
            file.setCompoundDrawablePadding(ui.dp(6));
            LayoutParams params = new LayoutParams(-2, -2);
            params.topMargin = ui.dp(content.getChildCount() == 0 ? 0 : 8);
            content.addView(file, params);
        }
        // The wrapper shrinks for short messages and bounds long content before measuring it.
        FrameLayout bubbleSlot = new FrameLayout(context);
        FrameLayout.LayoutParams bubbleSize = new FrameLayout.LayoutParams(-2, -2, Gravity.END);
        bubbleSlot.addView(content, bubbleSize);
        row.addView(bubbleSlot, new LayoutParams(0, -2, .88f));
        return row;
    }
}
