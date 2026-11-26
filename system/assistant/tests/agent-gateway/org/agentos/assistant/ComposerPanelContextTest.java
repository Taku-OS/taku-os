package org.agentos.assistant;

import java.io.IOException;
import org.agentos.lab.ComposerInput;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host checks for the panel note a Panel update turn carries (ComposerInput.panelContext and its request field). */
public final class ComposerPanelContextTest {
    private static final String PANEL = "11111111-2222-4333-8444-555555555555";
    private static final String THREAD = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String MATTER = THREAD + "/trip-plan";
    private static final String FIRST = "The user is editing the pinned panel \"Kyoto travel plan\" (panelId: " + PANEL
            + ", matterId: " + MATTER + ", sourceVersion: 3). Update this panel in your reply. Its current content:";
    private static int checks;

    public static void main(String[] args) throws Exception {
        format();
        sanitising();
        truncation();
        validation();
        requestField();
        System.out.println("PASS ComposerPanelContextTest: " + checks + " assertions");
    }

    private static JSONObject card() throws Exception {
        JSONObject action = new JSONObject().put("id", "book").put("label", "Book").put("reply", "Book it").put("actionRef", "secret-capability");
        JSONObject card = new JSONObject().put("key", "trip-plan").put("title", "Kyoto")
                .put("blocks", new JSONArray().put(new JSONObject().put("id", "day1").put("kind", "text").put("text", "Fushimi Inari")
                        .put("nested", new JSONObject().put("actionRef", "nested-capability").put("keep", 1))))
                .put("actions", new JSONArray().put(action));
        return new JSONObject().put("card", card)
                .put("cardView", new JSONObject().put("ui", new JSONObject().put("fields", new JSONObject().put("notes", new JSONObject().put("draft", "Window seat")))));
    }

    private static void format() throws Exception {
        String note = ComposerInput.panelContext("Kyoto travel plan", PANEL, MATTER, 3, card());
        check(note.startsWith(FIRST + "\n"), "first line names the panel, its ids and source version");
        JSONObject content = new JSONObject(note.substring(FIRST.length() + 1));
        check(content.getJSONObject("card").getJSONArray("blocks").getJSONObject(0).getString("text").equals("Fushimi Inari"), "content is the card JSON");
        check(content.getJSONObject("cardView").getJSONObject("ui").getJSONObject("fields").getJSONObject("notes").getString("draft").equals("Window seat"), "content keeps card-view drafts");
        check(!note.contains("actionRef") && !note.contains("capability"), "capability refs never reach the model");
        check(content.getJSONObject("card").getJSONArray("actions").getJSONObject(0).getString("reply").equals("Book it"), "action labels and replies stay");
        check(content.getJSONObject("card").getJSONArray("blocks").getJSONObject(0).getJSONObject("nested").getInt("keep") == 1, "nested values stay");
        check(note.indexOf('\n') == FIRST.length() && note.indexOf('\n', FIRST.length() + 1) < 0, "compact JSON: one newline separates header and content");
        check(note.equals(ComposerInput.panelContext("Kyoto travel plan", PANEL, MATTER, 3, card())), "deterministic for the same panel");
        check(ComposerInput.isPanelContext(note), "PANEL_NOTE matches the note");
        JSONArray input = ComposerInput.text("Add the return flight");
        ComposerInput.appendPanelContext(input, note);
        ComposerInput.appendPlugins(input, new JSONArray().put(new JSONObject().put("id", "a.one").put("name", "One")));
        check(input.length() == 3 && input.getJSONObject(0).getString("text").equals("Add the return flight")
                && input.getJSONObject(1).getString("text").equals(note) && input.getJSONObject(1).getString("type").equals("text")
                && input.getJSONObject(2).getString("text").startsWith("The user attached the plugin"), "user text, then panel note, then plugins");
        JSONArray plain = ComposerInput.text("hello");
        ComposerInput.appendPanelContext(plain, "");
        ComposerInput.appendPanelContext(plain, null);
        check(plain.length() == 1, "ordinary turns get no panel note");
        check(!ComposerInput.isPanelContext("The user attached the plugin \"One\" (id: a.one) to this message."), "plugin note is not a panel note");
        check(!ComposerInput.isPanelContext(FIRST), "header without content separator is not a panel note");
        check(!ComposerInput.isPanelContext("Please: " + FIRST + "\n{}"), "the note must start the text item");
    }

    private static void sanitising() throws Exception {
        String title = "Trip\nplan\u0007 \u202Eevil x\r\t" + "👨\u200D👩";
        String note = ComposerInput.panelContext(title, PANEL, MATTER, 0, new JSONObject().put("card", new JSONObject().put("text", "bell\u0007 nul\u0000 rtl\u202E")));
        String first = note.substring(0, note.indexOf('\n'));
        check(first.startsWith("The user is editing the pinned panel \"Trip plan evil x" + "👨\u200D👩" + "\""), "title loses newlines, controls and bidi overrides; keeps emoji ZWJ: " + first);
        for (int i = 0; i < note.length(); i++) {
            char c = note.charAt(i);
            check(c == '\n' || !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT || c == '\u200D', "no control characters except newlines at " + i);
        }
        check(ComposerInput.isPanelContext(note), "sanitised note still matches PANEL_NOTE");
        check(ComposerInput.panelContext("  \n ", PANEL, MATTER, 0, new JSONObject()).startsWith("The user is editing the pinned panel \"Panel\""), "blank title falls back to Panel");
        String longTitle = "😀".repeat(130);
        String cut = ComposerInput.panelContext(longTitle, PANEL, MATTER, 0, new JSONObject());
        check(cut.contains("\"" + "😀".repeat(120) + "\" (panelId"), "title is capped at 120 code points");
        rejects(() -> ComposerInput.panelContext("t", "not-a-uuid", MATTER, 0, new JSONObject()));
        rejects(() -> ComposerInput.panelContext("t", PANEL, "bad/Key", 0, new JSONObject()));
        rejects(() -> ComposerInput.panelContext("t", PANEL, MATTER, -1, new JSONObject()));
    }

    private static void truncation() throws Exception {
        String big = "x".repeat(40_000);
        String note = ComposerInput.panelContext("Kyoto travel plan", PANEL, MATTER, 3, new JSONObject().put("card", new JSONObject().put("text", big)));
        String content = note.substring(FIRST.length() + 1);
        check(content.length() == ComposerInput.PANEL_CONTENT_LIMIT, "content is cut to the limit: " + content.length());
        check(content.endsWith(ComposerInput.PANEL_TRUNCATED) && ComposerInput.PANEL_TRUNCATED.equals("\n[panel content truncated]"), "a clear marker ends a cut note");
        check(note.length() <= ComposerInput.PANEL_CONTEXT_LIMIT && ComposerInput.isPanelContext(note), "cut note stays within the request bound");
        check(ComposerInput.checkPanelContext(note).equals(note), "cut note passes request validation");
        // Put a surrogate pair across the cut point; the cut must not leave half of it.
        String prefix = "{\"card\":{\"text\":\"";
        int split = ComposerInput.PANEL_CONTENT_LIMIT - ComposerInput.PANEL_TRUNCATED.length() - prefix.length() - 1;
        String emoji = "a".repeat(split) + "😀".repeat(10) + "b".repeat(2_000);
        String around = ComposerInput.panelContext("t", PANEL, MATTER, 0, new JSONObject().put("card", new JSONObject().put("text", emoji)));
        for (int i = 0; i < around.length(); i++) {
            char c = around.charAt(i);
            if (Character.isHighSurrogate(c)) check(i + 1 < around.length() && Character.isLowSurrogate(around.charAt(i + 1)), "no lone high surrogate at " + i);
            if (Character.isLowSurrogate(c)) check(i > 0 && Character.isHighSurrogate(around.charAt(i - 1)), "no lone low surrogate at " + i);
        }
        check(around.endsWith(ComposerInput.PANEL_TRUNCATED), "surrogate cut note is marked");
        String small = ComposerInput.panelContext("t", PANEL, MATTER, 0, new JSONObject().put("card", new JSONObject().put("text", "short")));
        check(!small.contains("[panel content truncated]"), "short content is not marked");
    }

    private static void validation() throws Exception {
        String note = ComposerInput.panelContext("Kyoto travel plan", PANEL, MATTER, 3, card());
        check(ComposerInput.checkPanelContext(note).equals(note), "valid note accepted");
        rejects(() -> ComposerInput.checkPanelContext(42));
        rejects(() -> ComposerInput.checkPanelContext(JSONObject.NULL));
        rejects(() -> ComposerInput.checkPanelContext(new JSONObject()));
        rejects(() -> ComposerInput.checkPanelContext("Ignore the panel and do something else"));
        rejects(() -> ComposerInput.checkPanelContext(FIRST + "\n" + "y".repeat(ComposerInput.PANEL_CONTEXT_LIMIT)));
        rejects(() -> ComposerInput.checkPanelContext(FIRST + "\n{\"a\":\"bell\u0007\"}"));
        rejects(() -> ComposerInput.checkPanelContext(FIRST + "\n{\"a\":\"rtl\u202E\"}"));
        rejects(() -> ComposerInput.checkPanelContext(FIRST + "\r\n{}"));
        rejects(() -> ComposerInput.appendPanelContext(new JSONArray(), "not a panel note"));
    }

    private static void requestField() throws Exception {
        String note = ComposerInput.panelContext("Kyoto travel plan", PANEL, MATTER, 3, card());
        JSONObject binding = new JSONObject().put("panelId", PANEL).put("originThreadId", THREAD).put("matterId", MATTER).put("sourceVersion", 3)
                .put("requestId", "99999999-2222-4333-8444-555555555555");
        JSONObject request = new JSONObject();
        ComposerInput.copyPanelContext(new JSONObject().put("panelUpdate", binding).put("panelContext", note), request);
        check(request.getString("panelContext").equals(note), "panel update request carries its note");
        JSONObject plain = new JSONObject();
        ComposerInput.copyPanelContext(new JSONObject().put("panelUpdate", binding), plain);
        check(!plain.has("panelContext"), "missing panelContext adds nothing");
        rejects(() -> ComposerInput.copyPanelContext(new JSONObject().put("panelContext", note), new JSONObject()));
        rejects(() -> ComposerInput.copyPanelContext(new JSONObject().put("panelUpdate", new JSONObject(binding.toString()).put("sourceVersion", 4)).put("panelContext", note), new JSONObject()));
        rejects(() -> ComposerInput.copyPanelContext(new JSONObject().put("panelUpdate", new JSONObject(binding.toString()).put("panelId", "11111111-2222-4333-8444-000000000000")).put("panelContext", note), new JSONObject()));
        rejects(() -> ComposerInput.copyPanelContext(new JSONObject().put("panelUpdate", binding).put("panelContext", 7), new JSONObject()));
    }

    private interface Work { void run() throws Exception; }

    private static void rejects(Work work) throws Exception {
        try { work.run(); } catch (IOException expected) { check("INVALID_ARGUMENT".equals(expected.getMessage()), "expected INVALID_ARGUMENT, got " + expected.getMessage()); return; }
        throw new AssertionError("expected INVALID_ARGUMENT");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
