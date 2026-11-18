package org.agentos.tests;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

/** Real Android clipboard -> composer test; does not submit messages or emulate Mac IME input. */
public final class UnicodePasteTest extends UiAutomatorTestCase {
    public void testUnicodeClipboard() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        Runtime.getRuntime()
                .exec(
                        new String[] {
                            "/system/bin/am", "start", "-n", "org.agentos.assistant/.PanelActivity"
                        })
                .waitFor();
        UiObject back =
                new UiObject(new UiSelector().packageName("org.agentos.assistant").text("返回对话"));
        if (back.waitForExists(1800)) back.click();
        UiObject editor =
                new UiObject(
                        new UiSelector()
                                .packageName("org.agentos.assistant")
                                .description("Taku 对话输入框"));
        assertTrue(editor.waitForExists(8000));
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData originalClip = clipboard.getPrimaryClip();
        String originalText = editor.getText();
        String sample = "中文输入验证，标点与 Emoji：你好 🌱";
        editor.click();
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Taku Unicode test", sample));
            getUiDevice()
                    .pressKeyCode(
                            android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON);
            Runtime.getRuntime()
                    .exec(new String[] {"/system/bin/input", "keyevent", "KEYCODE_PASTE"})
                    .waitFor();
            sleep(700);
            assertEquals(
                    "Unicode clipboard paste must preserve CJK and emoji",
                    sample,
                    editor.getText());
            Runtime.getRuntime()
                    .exec(
                            new String[] {
                                "/system/bin/screencap",
                                "-p",
                                "/data/local/tmp/taku-unicode-paste.png"
                            })
                    .waitFor();
        } finally {
            clipboard.setPrimaryClip(ClipData.newPlainText("Taku restore", originalText));
            getUiDevice()
                    .pressKeyCode(
                            android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON);
            Runtime.getRuntime()
                    .exec(new String[] {"/system/bin/input", "keyevent", "KEYCODE_PASTE"})
                    .waitFor();
            if (originalClip != null) clipboard.setPrimaryClip(originalClip);
            else clipboard.clearPrimaryClip();
        }
    }
}
