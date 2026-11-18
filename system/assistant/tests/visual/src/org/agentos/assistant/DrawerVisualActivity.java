package org.agentos.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Test APK only. Synthetic native records exercise the production renderer; no controller/model
 * is started and no external action is executed. These screenshots are layout fixtures, not receipts.
 * Extras: locale=en-US|zh-CN (optional; otherwise system), case=normal|long, theme=light|dark.
 */
public final class DrawerVisualActivity extends Activity {
    private AgentDrawerView drawer;
    private boolean longCase;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Context renderContext = this;
        String language = getIntent().getStringExtra("locale");
        if (language != null && (language.equals("en-US") || language.equals("zh-CN"))) {
            Configuration configuration = new Configuration(getResources().getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(language)));
            // The Activity theme may already have accessed Resources before onCreate. Give only
            // the renderer its own configured context; never override initialized Activity state.
            renderContext = new ContextThemeWrapper(createConfigurationContext(configuration),
                    android.R.style.Theme_Material_Light_NoActionBar);
        }
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.setBlurBehindRadius(20);
        getWindow().setAttributes(attributes);
        String theme = getIntent().getStringExtra("theme");
        getSharedPreferences("agent-settings", 0).edit()
                .putString("theme", "dark".equals(theme) ? "dark" : "light").apply();
        drawer = new AgentDrawerView(renderContext, new AgentDrawerView.Host() {
            public void dismiss() { finish(); }
            public android.graphics.Bitmap screenshot() { return null; }
            public void openMirror() {}
            public String foreground() { return "visual-fixture-only"; }
        });
        setContentView(drawer);
        longCase = "long".equals(getIntent().getStringExtra("case"));
        try {
            boolean chinese = drawer.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
            String title = chinese ? "羊毛大衣" : "Wool coat";
            String request = chinese ? "找到我上周看到的那件大衣，比较一下价格。"
                    : "Find the coat I saw last week and compare prices.";
            String response = chinese ? "找到了，是 10 月 8 日收藏在 Pinterest 的那件。正在比较四家店的价格。"
                    : "Found it — Pinterest, Oct 8. Comparing four stores now.";
            if (longCase) {
                title = chinese ? "比较上周收藏的羊毛大衣：尺码、材质和配送时间"
                        : "Compare the wool coat I saved last week: sizes, fabric and delivery";
                request = chinese ? "找到我上周在 Pinterest 看到的那件羊毛大衣，比较不同店铺的价格和尺码。\n\n"
                        + "我想要深灰色的中码，材质里至少有 70% 的羊毛。看看配送时间和退换规则，先把合适的选项整理给我，不要下单。"
                        : "Find the wool coat I saw on Pinterest last week and compare prices and sizes across stores.\n\n"
                        + "I want medium in charcoal, with at least 70% wool. Check delivery dates and returns, then show me the suitable options before buying anything.";
                response = chinese ? "找到了，是 10 月 8 日收藏的那件。正在核对四家店的价格、材质和中码库存。\n\n"
                        + "接下来会把符合条件的选项放在一起，方便你比较配送时间和退换规则。还没有购买任何商品。"
                        : "Found it in your October 8 Pinterest saves. I’m checking four stores for price, wool content and medium availability.\n\n"
                        + "I’ll bring the suitable options together so you can compare delivery and returns. Nothing has been purchased.";
            }
            JSONArray items = new JSONArray();
            items.put(user(request).put("id", "fixture-request"));
            items.put(new JSONObject().put("id", "fixture-progress").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", chinese ? "查看收藏记录和商品页面。" : "Checking saved items and product listings."));
            items.put(new JSONObject().put("id", "fixture-observe").put("type", "dynamicToolCall")
                    .put("tool", "taku_phone__observe_background").put("namespace", "taku_phone")
                    .put("status", "completed").put("success", true)
                    .put("arguments", new JSONObject().put("fixtureOnly", true).put("screenKey", "fixture-retail")));
            items.put(new JSONObject().put("id", "fixture-search").put("type", "webSearch")
                    .put("status", "inProgress").put("query", "fixture: charcoal wool coat price comparison"));
            items.put(new JSONObject().put("id", "fixture-answer").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", response));
            JSONObject thread = new JSONObject().put("id", "00000000-0000-0000-0000-000000000000")
                    .put("name", title).put("turns", new JSONArray().put(new JSONObject()
                            .put("id", "fixture-turn").put("status", "inProgress").put("items", items)));
            drawer.conversation(AgentConversation.read(thread), new JSONObject());
            drawer.postDelayed(this::layoutReport, 700);
        } catch (Throwable error) { writeReport(null, error); }
    }

    private JSONObject user(String value) throws JSONException {
        return new JSONObject().put("type", "userMessage").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", value)));
    }

    private void layoutReport() {
        try {
            JSONArray issues = new JSONArray(), controls = new JSONArray();
            Rect viewport = bounds(drawer);
            View title = description(drawer, drawer.getContext().getString(R.string.chat_current_title));
            View composer = description(drawer, drawer.getContext().getString(R.string.chat_input));
            checkBounds(title, "conversation title", viewport, issues);
            checkBounds(composer, "composer", viewport, issues);
            if (composer != null) {
                Rect box = bounds(composer);
                if (box.top < viewport.top || box.bottom > viewport.bottom)
                    issues.put("composer is outside the allocated height");
            }
            inspectButtons(drawer, viewport, controls, issues);
            if (controls.length() < 3) issues.put("expected visible navigation and composer controls");
            JSONObject report = new JSONObject().put("passed", issues.length() == 0)
                    .put("fixtureOnly", true).put("modelInvoked", false).put("externalActionsExecuted", false)
                    .put("locale", drawer.getResources().getConfiguration().getLocales().get(0).toLanguageTag())
                    .put("case", longCase ? "long" : "normal")
                    .put("widthDp", drawer.getWidth() / drawer.getResources().getDisplayMetrics().density)
                    .put("heightDp", drawer.getHeight() / drawer.getResources().getDisplayMetrics().density)
                    .put("fontScale", drawer.getResources().getConfiguration().fontScale)
                    .put("visibleControls", controls).put("issues", issues);
            writeReport(report, null);
        } catch (Throwable error) { writeReport(null, error); }
    }

    private void inspectButtons(View view, Rect viewport, JSONArray controls, JSONArray issues) throws JSONException {
        if (!view.isShown()) return;
        Rect clipped = new Rect();
        if (!view.getGlobalVisibleRect(clipped) || clipped.isEmpty()) return;
        if (view instanceof Button || view instanceof ImageButton) {
            String label = view.getContentDescription() == null ? view instanceof TextView
                    ? ((TextView) view).getText().toString() : view.getClass().getSimpleName()
                    : view.getContentDescription().toString();
            Rect box = bounds(view);
            checkBounds(view, label, viewport, issues);
            float density = drawer.getResources().getDisplayMetrics().density;
            if (box.width() + 1 < 48 * density || box.height() + 1 < 48 * density)
                issues.put(label + ": touch target below 48 dp");
            controls.put(new JSONObject().put("label", label).put("widthDp", box.width() / density)
                    .put("heightDp", box.height() / density).put("enabled", view.isEnabled()));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            inspectButtons(((ViewGroup) view).getChildAt(i), viewport, controls, issues);
    }

    private void checkBounds(View view, String label, Rect viewport, JSONArray issues) {
        if (view == null || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            issues.put(label + ": missing or unmeasured");
            return;
        }
        Rect box = bounds(view);
        if (box.left < viewport.left || box.right > viewport.right)
            issues.put(label + ": horizontal overflow");
    }

    private Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private void writeReport(JSONObject report, Throwable error) {
        try (FileOutputStream out = openFileOutput("drawer-layout.json", MODE_PRIVATE)) {
            if (report == null) report = new JSONObject().put("passed", false).put("fixtureOnly", true)
                    .put("error", error == null ? "No report" : error.toString());
            out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Override protected void onDestroy() {
        if (drawer != null) drawer.destroy();
        super.onDestroy();
    }
}
