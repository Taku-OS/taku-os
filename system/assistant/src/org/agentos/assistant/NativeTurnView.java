package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** One native assistant segment: speaker, answer, then its optional Activity disclosure. */
final class NativeTurnView extends LinearLayout {
    private final DrawerTheme theme;
    private final ConversationExpansion expansion;
    private final NativeToolPresentation.Labels labels;
    private final LinearLayout header, activities, answers, footer;
    private final TextView summary, failures, state;
    private final ImageView arrow, activityMark;
    private final ImageButton copy;
    private final Button read;
    private org.agentos.audio.AudioReader reader;
    private org.agentos.audio.SpeechChunks.Stream speechStream;
    private static NativeTurnView reading;
    private final Map<String, NativeActivityView> activityViews = new LinkedHashMap<>();
    private final Map<String, NativeMarkdownView> answerViews = new LinkedHashMap<>();
    private final Map<String, String> renderedAnswers = new HashMap<>();
    private NativeTurnPresentation.Block block;
    private JSONObject outputs;

    NativeTurnView(Context context, DrawerTheme theme, ConversationExpansion expansion) {
        super(context);
        this.theme = theme;
        this.expansion = expansion;
        labels = NativeActivityView.labels(context);
        AgentSettingsUi ui = new AgentSettingsUi(context, theme);
        setOrientation(VERTICAL);
        LinearLayout speaker = new LinearLayout(context);
        speaker.setGravity(Gravity.CENTER_VERTICAL);
        ImageView tako = new ImageView(context);
        tako.setImageResource(R.drawable.ic_tako_mark);
        tako.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        speaker.addView(tako, new LayoutParams(ui.dp(24), ui.dp(24)));
        TextView name = ui.text(context.getString(R.string.chat_speaker), 12, false);
        ChatDesign.text(name, ChatDesign.Role.CAPTION);
        LayoutParams nameSize = new LayoutParams(-2, -2);
        nameSize.setMarginStart(ui.dp(8));
        speaker.addView(name, nameSize);
        LayoutParams speakerSize = new LayoutParams(-1, -2);
        speakerSize.bottomMargin = ui.dp(8);
        addView(speaker, speakerSize);
        // Keep reply actions off the reading surface. This speaker row owns the
        // context action; selectable message TextViews keep native text selection.
        speaker.setOnLongClickListener(view -> {
            if (!canCopy()) return false;
            PopupMenu menu = new PopupMenu(context, speaker);
            menu.getMenu().add(context.getString(R.string.chat_copy_reply))
                    .setOnMenuItemClickListener(item -> { copyReply(); return true; });
            menu.show();
            return true;
        });
        speaker.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (canCopy()) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY,
                        context.getString(R.string.chat_copy_reply)));
            }
            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY && canCopy()) {
                    copyReply(); return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        answers = ui.column();
        addView(answers);
        state = ui.text("", 14, false);
        ChatDesign.text(state, ChatDesign.Role.META);
        state.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(state);
        footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(ui.dp(48));
        header.setFocusable(true);
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.border), null, null));
        activityMark = new ImageView(context);
        activityMark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams markSize = new LayoutParams(ui.dp(16), ui.dp(16));
        markSize.setMarginEnd(ui.dp(8));
        header.addView(activityMark, markSize);
        summary = ui.text("", 14, false);
        ChatDesign.text(summary, ChatDesign.Role.META);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(summary, new LayoutParams(-2, -2, 1));
        arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_drawer_chevron_down);
        arrow.setColorFilter(theme.muted);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams chevron = new LayoutParams(ui.dp(16), ui.dp(16));
        chevron.setMarginStart(ui.dp(8));
        header.addView(arrow, chevron);
        failures = ui.text("", 12, false);
        failures.setTextColor(ChatDesign.color(context, R.color.taku_status_error));
        ChatDesign.text(failures, ChatDesign.Role.CAPTION);
        LayoutParams failureSize = new LayoutParams(-2, -2);
        failureSize.setMarginStart(ui.dp(8));
        header.addView(failures, failureSize);
        // Wrap the action group; only shrink Activity when space is constrained.
        // Copy stays beside the disclosure instead of at the far edge of the reply.
        footer.addView(header, new LayoutParams(-2, -2, 1));
        copy = new ImageButton(context);
        copy.setImageResource(R.drawable.ic_taku_copy);
        copy.setScaleType(ImageView.ScaleType.FIT_CENTER);
        copy.setImageTintList(ColorStateList.valueOf(theme.muted));
        copy.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.border), null, null));
        copy.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        copy.setContentDescription(context.getString(R.string.chat_copy_reply));
        footer.addView(copy, new LayoutParams(ui.dp(48), ui.dp(48)));
        read=new Button(context);read.setText("Read aloud");read.setContentDescription("Read reply aloud");read.setTooltipText("Tap to read aloud; long-press for voice settings");
        footer.addView(read, new LayoutParams(-2, ui.dp(44)));
        read.setOnClickListener(v->{if(reading==this)stopReading(null);else stopReading(this::beginReading);});
        read.setOnLongClickListener(v->{getContext().startActivity(new android.content.Intent().setClassName("org.agentos.audio","org.agentos.audio.AudioSettingsActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));return true;});
        addView(footer, new LayoutParams(-2, -2));
        activities = ui.column();
        activities.setPaddingRelative(ui.dp(20), 0, 0, 0);
        addView(activities);
        // A real screen/result can follow this segment; no placeholder is emitted.
        copy.setOnClickListener(view -> copyReply());
        header.setOnClickListener(view -> {
            if (block == null) return;
            expansion.toggle(block.key);
            bindActivities();
        });
    }

    void bind(NativeTurnPresentation.Block block, JSONObject outputs) {
        this.block = block;
        this.outputs = outputs;
        summary.setText(getContext().getString(R.string.chat_activity_count, block.activities.size()));
        ChatDesign.text(summary, ChatDesign.Role.META);
        failures.setText(block.failures() == 0 ? "" : getContext().getResources().getQuantityString(
                R.plurals.chat_activity_failures, block.failures(), block.failures()));
        ChatDesign.text(failures, ChatDesign.Role.CAPTION);
        failures.setVisibility(block.failures() == 0 ? GONE : VISIBLE);
        activityMark.setImageResource(block.status.equals("completed")
                ? R.drawable.ic_taku_check : R.drawable.ic_schedule);
        activityMark.setColorFilter(theme.muted);
        header.setVisibility(block.activities.isEmpty() ? GONE : VISIBLE);
        bindActivities();
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < block.answers.size(); i++) {
            AgentConversation.Entry entry = block.answers.get(i);
            String key = entry.key();
            retained.add(key);
            NativeMarkdownView answer = answerViews.get(key);
            if (answer == null) {
                answer = new NativeMarkdownView(getContext(), theme);
                answerViews.put(key, answer);
            }
            NativeConversationView.place(answers, answer, i, theme.dp(getContext(), i == 0 ? 0 : 12));
            String value = NativeTurnPresentation.visibleText(entry.text, block.active);
            if (entry.kind == AgentConversation.Kind.PLAN)
                value = "### " + getContext().getString(R.string.chat_plan) + "\n\n" + value;
            if (!value.equals(renderedAnswers.get(key))) {
                // Stable item keys preserve selection and completed text during streaming.
                answer.bind(value);
                renderedAnswers.put(key, value);
            }
        }
        Iterator<Map.Entry<String, NativeMarkdownView>> iterator = answerViews.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, NativeMarkdownView> item = iterator.next();
            if (!retained.contains(item.getKey())) {
                answers.removeView(item.getValue());
                renderedAnswers.remove(item.getKey());
                iterator.remove();
            }
        }
        boolean failed = block.status.equals("failed");
        boolean stopped = block.status.equals("interrupted") || block.status.equals("cancelled")
                || block.status.equals("canceled");
        // Once an answer exists, ordinary progress belongs in Activity. Actual failures/stops
        // stay visible even when earlier answer text survives the failed turn.
        boolean showState = failed || stopped || block.active && block.answers.isEmpty();
        state.setText(stateText());
        state.setTextColor(failed ? ChatDesign.color(getContext(), R.color.taku_status_error) : theme.muted);
        ChatDesign.text(state, ChatDesign.Role.META);
        state.setPadding(0, theme.dp(getContext(), block.answers.isEmpty() ? 0 : 8), 0, 0);
        state.setVisibility(showState ? VISIBLE : GONE);
        // Copy may share a real Activity row, but must not create its own 48dp
        // blank row after every answer. Long-press and accessibility remain.
        copy.setVisibility(canCopy() && !block.activities.isEmpty() ? VISIBLE : GONE);
        // Read-aloud needs the Audio Engine, which a clean image may not include yet (issue #26 #4).
        read.setVisibility(AudioEngine.available(getContext()) && (block.active || block.status.equals("completed")) && block.answers.stream().anyMatch(e->e.kind==AgentConversation.Kind.ASSISTANT && !e.text.isBlank()) ? VISIBLE : GONE);
        feedReading();
        footer.setVisibility(!block.activities.isEmpty() || read.getVisibility() == VISIBLE ? VISIBLE : GONE);
    }

    private boolean canCopy() { return block != null && block.terminal() && !block.answers.isEmpty(); }

    private void copyReply() {
        if (!canCopy()) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getContext().getString(R.string.chat_speaker), block.answerText()));
    }

    private String stateText() {
        if (block.status.equals("failed")) return getContext().getString(R.string.chat_turn_failed);
        if (block.status.equals("interrupted") || block.status.equals("cancelled")
                || block.status.equals("canceled")) return getContext().getString(R.string.chat_turn_stopped);
        if (block.active) {
            for (int i = block.activities.size() - 1; i >= 0; i--) {
                AgentConversation.Entry entry = block.activities.get(i);
                if (entry.kind == AgentConversation.Kind.TOOL
                        && NativeToolPresentation.status(entry.item).equals("In progress"))
                    return NativeToolPresentation.title(entry.item, labels) + "…";
            }
            return getContext().getString(R.string.chat_working);
        }
        return "";
    }

    static boolean isReading(){return reading!=null && reading.reader!=null && reading.reader.getActive();}
    static void stopReading(Runnable after){
        NativeTurnView old=reading;
        if(old!=null && old.reader!=null && old.reader.getActive())old.reader.stop(after);
        else{reading=null;if(after!=null)after.run();}
    }
    private String readingText(){
        StringBuilder source=new StringBuilder();
        for(AgentConversation.Entry entry:block.answers)if(entry.kind==AgentConversation.Kind.ASSISTANT)source.append(NativeTurnPresentation.visibleText(entry.text,block.active)).append("\n\n");
        return NativeMarkdown.speechText(source.toString());
    }
    private void beginReading(){
        if(!isAttachedToWindow()||!isShown()||block==null||!(block.active||block.status.equals("completed")))return;
        String plain=readingText();
        if(plain.isBlank())return;
        String language=plain.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)?"zh-CN":"en-US";
        reading=this;
        reader=new org.agentos.audio.AudioReader(getContext(),(active,message)->{
            read.setText(active?"Stop reading":"Read aloud");read.setStateDescription(message);read.setContentDescription(active?"Stop reading":"Read reply aloud");
            if(!active){if(reading==this)reading=null;if(!message.equals("Reading complete")&&!message.equals("Reading stopped"))Toast.makeText(getContext(),message,Toast.LENGTH_LONG).show();}
        });
        if(block.active){speechStream=new org.agentos.audio.SpeechChunks.Stream();reader.startStream(language);feedReading();}
        else{speechStream=null;reader.start(plain,language);}
    }
    private void feedReading(){
        if(reading!=this || reader==null || !reader.getActive() || speechStream==null || speechStream.getFinished())return;
        if(!block.active && !block.status.equals("completed")){stopReading(null);return;}
        try{
            boolean complete=block.status.equals("completed");
            for(String sentence:speechStream.accept(readingText(),complete))reader.append(sentence);
            if(complete)reader.finishInput();
        }catch(Exception error){stopReading(null);Toast.makeText(getContext(),"Reading stopped because the reply changed or too much content was queued",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onDetachedFromWindow(){if(reading==this)stopReading(null);super.onDetachedFromWindow();}

    private void bindActivities() {
        boolean open = expansion.contains(block.key);
        header.setContentDescription(getContext().getString(open
                ? R.string.chat_collapse_activity : R.string.chat_expand_activity));
        header.setStateDescription(summary.getText());
        arrow.setRotation(open ? 0 : -90);
        activities.setVisibility(open && !block.activities.isEmpty() ? VISIBLE : GONE);
        if (!open) return;
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < block.activities.size(); i++) {
            AgentConversation.Entry entry = block.activities.get(i);
            retained.add(entry.key());
            NativeActivityView row = activityViews.get(entry.key());
            if (row == null) {
                row = new NativeActivityView(getContext(), theme, expansion);
                activityViews.put(entry.key(), row);
            }
            NativeConversationView.place(activities, row, i, 0);
            row.bind(entry, outputs);
        }
        Iterator<Map.Entry<String, NativeActivityView>> iterator = activityViews.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, NativeActivityView> item = iterator.next();
            if (!retained.contains(item.getKey())) {
                activities.removeView(item.getValue());
                iterator.remove();
            }
        }
    }
}
