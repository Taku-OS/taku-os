package org.agentos.lab;

import java.io.IOException;
import java.util.List;
import static org.agentos.lab.PhoneDraftTarget.*;

public final class PhoneDraftTargetTest {
    private static final String PACKAGE = "com.tencent.mm";
    private static final String TITLE = "Dinner plans (3)";
    private static final String DRAFT = "Booked for 3 at 20:00. Seafood allergy noted.\n我来手动发送。";
    private static final Box WINDOW = new Box(0, 80, 1080, 2200);
    private static final Box HEADER = new Box(200, 100, 800, 180);
    private static final Box EDITOR = new Box(100, 1950, 900, 2070);
    private static int checks;
    interface Work { void run() throws Exception; }

    static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    static void rejects(String code, Work work) throws Exception {
        try { work.run(); }
        catch (IOException error) {
            check(code.equals(error.getMessage()));
            return;
        }
        throw new AssertionError("Expected " + code);
    }
    static Window window(int id, int display, int layer, boolean active, boolean focused, String pkg) {
        return new Window(id, display, layer, true, active, focused, pkg, WINDOW);
    }
    static Node title(int key, String text, Box bounds, boolean scrollable) {
        return new Node(key, PACKAGE, bounds, true, true, false, false, false, scrollable, text, "");
    }
    static Node editor(int key, String draft, Box bounds, boolean visible, boolean enabled,
                       boolean password, boolean writable, String pkg) {
        return new Node(key, pkg, bounds, visible, enabled, true, password, writable, false, "", draft);
    }
    static Node editor(String draft) { return editor(2, draft, EDITOR, true, true, false, true, PACKAGE); }

    public static void main(String[] args) throws Exception {
        validateRequest(PACKAGE, TITLE, DRAFT); checks++;
        rejects("INVALID_PACKAGE_NAME", () -> validateRequest("com.tencent.mm;input keyevent 66", TITLE, DRAFT));
        rejects("INVALID_PACKAGE_NAME", () -> validateRequest(null, TITLE, DRAFT));
        rejects("INVALID_CONVERSATION_TITLE", () -> validateRequest(PACKAGE, "\t\n", DRAFT));
        rejects("INVALID_CONVERSATION_TITLE", () -> validateRequest(PACKAGE, "a".repeat(161), DRAFT));
        rejects("INVALID_DRAFT_TEXT", () -> validateRequest(PACKAGE, TITLE, "a".repeat(4001)));
        rejects("INVALID_DRAFT_TEXT", () -> validateRequest(PACKAGE, TITLE, "\r"));
        rejects("INVALID_DRAFT_TEXT", () -> validateRequest(PACKAGE, TITLE, "hello\u0000world"));
        rejects("INVALID_DRAFT_TEXT", () -> validateRequest(PACKAGE, TITLE, " \n"));
        Window main = window(10, 0, 5, true, true, PACKAGE);
        Window background = window(20, 2, 100, true, true, PACKAGE);
        check(selectWindow(List.of(main, background), PACKAGE).id() == 10);
        rejects("NO_FOREGROUND_WINDOW", () -> selectWindow(List.of(background), PACKAGE));
        rejects("FOREGROUND_PACKAGE_MISMATCH", () -> selectWindow(List.of(main,
                window(11, 0, 6, true, true, "com.other.app")), PACKAGE));
        rejects("FOREGROUND_WINDOW_NOT_ACTIVE", () -> selectWindow(List.of(
                window(10, 0, 5, false, true, PACKAGE)), PACKAGE));
        rejects("FOREGROUND_WINDOW_NOT_ACTIVE", () -> selectWindow(List.of(
                window(10, 0, 5, true, false, PACKAGE)), PACKAGE));
        rejects("AMBIGUOUS_FOREGROUND_WINDOW", () -> selectWindow(List.of(main,
                window(11, 0, 5, true, true, PACKAGE)), PACKAGE));
        check(selectWindow(List.of(window(8, 0, 2, false, false, PACKAGE),
                window(9, 0, 2, false, false, PACKAGE), main), PACKAGE).id() == 10);
        Node header = title(1, TITLE, HEADER, false);
        Selection empty = select(main, List.of(header, editor("")), PACKAGE, TITLE, DRAFT);
        check(empty.titleKey() == 1 && empty.editorKey() == 2 && !empty.alreadyPrepared());
        check(select(main, List.of(header, editor(DRAFT)), PACKAGE, TITLE, DRAFT).alreadyPrepared());
        check(!alreadyPrepared(null, DRAFT));
        check(alreadyPrepared(DRAFT, DRAFT));
        // The adapter invokes this same guard again after refreshing the editor before writing.
        rejects("EXISTING_DRAFT_PRESERVED", () -> alreadyPrepared("user typed while inspecting", DRAFT));
        // Even whitespace in an existing draft is user content; never normalize or erase it.
        rejects("EXISTING_DRAFT_PRESERVED", () -> select(main, List.of(header, editor(" ")), PACKAGE, TITLE, DRAFT));
        rejects("EXISTING_DRAFT_PRESERVED", () -> select(main, List.of(header, editor("private unfinished message")), PACKAGE, TITLE, DRAFT));
        rejects("EXISTING_DRAFT_PRESERVED", () -> select(main, List.of(header, editor(DRAFT + " ")), PACKAGE, TITLE, DRAFT));
        check(!select(main, List.of(title(1, " Dinner\u00a0plans  (3)\n", HEADER, false), editor("")),
                PACKAGE, "Dinner plans (3)", DRAFT).alreadyPrepared());
        // A title substring, wrong member count, body text or scrollable message must not authorize writing.
        rejects("CONVERSATION_TITLE_NOT_VERIFIED", () -> select(main, List.of(header, editor("")), PACKAGE, "Dinner plans", DRAFT));
        rejects("CONVERSATION_TITLE_NOT_VERIFIED", () -> select(main, List.of(header, editor("")), PACKAGE, "Dinner plans (4)", DRAFT));
        rejects("CONVERSATION_TITLE_NOT_VERIFIED", () -> select(main, List.of(
                title(1, TITLE, new Box(100, 700, 800, 780), false), editor("")), PACKAGE, TITLE, DRAFT));
        rejects("CONVERSATION_TITLE_NOT_VERIFIED", () -> select(main, List.of(
                title(1, TITLE, HEADER, true), editor("")), PACKAGE, TITLE, DRAFT));
        rejects("AMBIGUOUS_CONVERSATION_TITLE", () -> select(main, List.of(header,
                title(3, TITLE, HEADER, false), editor("")), PACKAGE, TITLE, DRAFT));
        rejects("NO_FOREGROUND_EDITOR", () -> select(main, List.of(header), PACKAGE, TITLE, DRAFT));
        rejects("AMBIGUOUS_FOREGROUND_EDITOR", () -> select(main, List.of(header, editor(""),
                editor(3, "", EDITOR, true, false, true, false, PACKAGE)), PACKAGE, TITLE, DRAFT));
        rejects("PASSWORD_EDITOR_REJECTED", () -> select(main, List.of(header,
                editor(2, "", EDITOR, true, true, true, true, PACKAGE)), PACKAGE, TITLE, DRAFT));
        rejects("EDITOR_PACKAGE_MISMATCH", () -> select(main, List.of(header,
                editor(2, "", EDITOR, true, true, false, true, "com.other.app")), PACKAGE, TITLE, DRAFT));
        rejects("EDITOR_NOT_WRITABLE", () -> select(main, List.of(header,
                editor(2, "", EDITOR, true, true, false, false, PACKAGE)), PACKAGE, TITLE, DRAFT));
        rejects("EDITOR_NOT_WRITABLE", () -> select(main, List.of(header,
                editor(2, "", EDITOR, true, false, false, true, PACKAGE)), PACKAGE, TITLE, DRAFT));
        rejects("EDITOR_POSITION_NOT_VERIFIED", () -> select(main, List.of(header,
                editor(2, "", HEADER, true, true, false, true, PACKAGE)), PACKAGE, TITLE, DRAFT));
        rejects("EDITOR_POSITION_NOT_VERIFIED", () -> select(main, List.of(header,
                editor(2, "", new Box(0, 2100, 1080, 2300), true, true, false, true, PACKAGE)), PACKAGE, TITLE, DRAFT));
        check(!select(main, List.of(header, editor(""),
                editor(3, "invisible value", EDITOR, false, true, false, true, PACKAGE)), PACKAGE, TITLE, DRAFT).alreadyPrepared());
        System.out.println("PhoneDraftTargetTest: " + checks + " checks passed");
    }
}
