package org.agentos.assistant;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;

/** Test APK only: deterministic view identity/Unicode checks, never a model acceptance test. */
public final class ConversationRendererActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int checks, frame;
    private NativeConversationView transcript;
    private SharedPreferences preferences;
    private JSONObject thread, answer;
    private TextView originalAnswer, previousAnswer;
    private final String[] deltas = {"中", "中文", "中文\uD83D", "中文😊", "中文😊，正在", "中文😊，正在逐字输出。",
            "中文😊，正在逐字输出。\n\n**粗体", "中文😊，正在逐字输出。\n\n**粗体中文**与 `代码`。\n\n```text\n尚未闭合的代码",
            "中文😊，正在逐字输出。\n\n**粗体中文**与 `代码`。\n\n```text\n尚未闭合的代码\n```"};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("renderer-test", 0);
        preferences.edit().clear().commit();
        try {
            thread = new JSONObject("{id:'renderer-fixture',turns:[{id:'previous',status:'completed',items:["
                    + "{id:'old',type:'agentMessage',phase:'final_answer',text:'之前的正文保留原视图'}]},"
                    + "{id:'current',status:'inProgress',items:["
                    + "{id:'u',type:'userMessage',content:[{type:'text',text:'验证中文流式'}]},"
                    + "{id:'c',type:'agentMessage',phase:'commentary',text:'检查中文输入'},"
                    + "{id:'tool',type:'commandExecution',command:'printf 中文',status:'completed',exitCode:0,aggregatedOutput:'中文'},"
                    + "{id:'a',type:'agentMessage',phase:'final_answer',text:'中'}]}]}");
            answer = thread.getJSONArray("turns").getJSONObject(1).getJSONArray("items").getJSONObject(3);
            show("renderer-fixture");
            originalAnswer = (TextView) text(transcript, "中");
            previousAnswer = (TextView) text(transcript, "之前的正文保留原视图");
            android.text.Selection.setSelection((android.text.Spannable) previousAnswer.getText(), 0, 2);
            check(originalAnswer != null, "first Chinese character appears immediately");
            check(text(transcript, "检查中文输入") == null, "default work log does not mount activity details");
            check(description(transcript, getString(R.string.chat_expand_activity)) != null, "collapsed work log is available");
            check(text(transcript, getString(R.string.chat_speaker)) != null, "assistant has a named speaker");
            View responseRow = transcriptRow(originalAnswer);
            String anchor = transcript.anchorKey(responseRow);
            check(!anchor.isEmpty() && transcript.rowForKey(anchor) == responseRow,
                    "reading anchor resolves to the same native transcript row");
            handler.postDelayed(this::nextFrame, 100);
        } catch (Throwable error) { finishReport(error); }
    }

    private void show(String id) throws Exception {
        transcript = new NativeConversationView(this, new DrawerTheme(false), preferences, id);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(24, 24, 24, 24);
        scroll.addView(transcript);
        setContentView(scroll);
        transcript.bind(AgentConversation.read(thread), null);
    }

    private void nextFrame() {
        try {
            String value = deltas[frame++];
            answer.put("text", value);
            transcript.bind(AgentConversation.read(thread), null);
            String expected = NativeMarkdown.render(NativeTurnPresentation.visibleText(value, true).split("\\n\\n", 2)[0], 0).toString();
            check(text(transcript, expected) == originalAnswer, "same live TextView for Chinese delta " + frame);
            check(text(transcript, "之前的正文保留原视图") == previousAnswer, "completed answer remains mounted " + frame);
            check(previousAnswer.getSelectionStart() == 0 && previousAnswer.getSelectionEnd() == 2, "stream keeps selection in older text " + frame);
            check(originalAnswer.getText().toString().indexOf('\uFFFD') < 0, "no broken UTF-16 replacement " + frame);
            if (frame < deltas.length) { handler.postDelayed(this::nextFrame, 100); return; }
            check(originalAnswer.getWidth() > 0 && originalAnswer.getHeight() > 0, "streamed answer was laid out on device");
            View activity = description(transcript, getString(R.string.chat_expand_activity));
            int[] answerPosition = new int[2], activityPosition = new int[2];
            originalAnswer.getLocationInWindow(answerPosition);
            activity.getLocationInWindow(activityPosition);
            check(activityPosition[1] >= answerPosition[1] + originalAnswer.getHeight(),
                    "useful response precedes its activity disclosure");
            check(activity.getHeight() >= Math.round(48 * getResources().getDisplayMetrics().density),
                    "activity disclosure has a full mobile touch target");
            activity.performClick();
            check(text(transcript, "检查中文输入") != null, "open log exposes real commentary");
            String tool = getString(R.string.chat_tool_command) + " · " + getString(R.string.chat_tool_completed);
            description(transcript, getString(R.string.chat_expand_item, tool)).performClick();
            check(description(transcript, getString(R.string.chat_collapse_item, tool)) != null, "native tool details can expand");
            thread.getJSONArray("turns").getJSONObject(1).put("status", "failed");
            transcript.bind(AgentConversation.read(thread), null);
            check(text(transcript, getString(R.string.chat_turn_failed)).isShown(),
                    "real failure stays visible beside a preserved answer");
            check(text(transcript, expected) == originalAnswer, "failure preserves usable response view");
            thread.getJSONArray("turns").getJSONObject(1).put("status", "completed");
            transcript.bind(AgentConversation.read(thread), null);
            check(description(transcript, getString(R.string.chat_collapse_activity)) != null, "completion preserves expansion");
            check(text(transcript, expected) == originalAnswer, "completion preserves response view");
            show("renderer-fixture");
            check(description(transcript, getString(R.string.chat_collapse_activity)) != null, "recreation preserves expansion");
            check(description(transcript, getString(R.string.chat_collapse_item, tool)) != null, "recreation preserves nested detail");
            show("different-session");
            check(description(transcript, getString(R.string.chat_expand_activity)) != null, "other session stays collapsed");
            check(text(transcript, "检查中文输入") == null, "expansion never leaks across sessions");
            JSONObject runtime = new JSONObject("{plan:{plan:[{step:'真实计划步骤',status:'pending'}]},error:'原生错误',requests:[{key:'fixture-approval',method:'item/commandExecution/requestApproval',params:{reason:'测试授权请求'}}]}");
            NativeTimelineView timeline = new NativeTimelineView(this, new DrawerTheme(false), runtime, transcript.expansion());
            transcript.addView(timeline);
            check(text(timeline, "原生错误").isShown(), "native error remains visible outside folded details");
            check(text(timeline, "等待你的选择").isShown(), "native approval remains visible outside folded details");
            check(!text(timeline, "○ 真实计划步骤").isShown(), "native runtime plan defaults to collapsed");
            text(timeline, "展开计划与变更").performClick();
            check(text(timeline, "○ 真实计划步骤").isShown(), "native runtime plan expands on request");
            finishReport(null);
        } catch (Throwable error) { finishReport(error); }
    }

    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private View transcriptRow(View child) {
        while (child.getParent() instanceof View && child.getParent() != transcript)
            child = (View) child.getParent();
        return child;
    }

    private View text(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(value)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private View description(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private void finishReport(Throwable error) {
        try (FileOutputStream out = openFileOutput("conversation-renderer.json", MODE_PRIVATE)) {
            JSONObject result = new JSONObject().put("passed", error == null).put("assertions", checks)
                    .put("streamFrames", frame).put("fixtureOnly", true);
            if (error != null) result.put("error", error.toString());
            out.write(result.toString(2).getBytes("UTF-8"));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
