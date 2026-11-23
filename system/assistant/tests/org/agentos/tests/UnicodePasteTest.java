package org.agentos.tests;

import android.content.ClipData;
import android.content.ClipboardManager;

import android.view.KeyEvent;
import com.android.uiautomator.core.Configurator;
import org.json.JSONObject;

/** Real Android clipboard -> composer test; does not submit messages or emulate Mac IME input. */
public final class UnicodePasteTest extends AgentInterfaceTest {
    public void testUnicodeClipboard() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        connect();
        String thread = access("composer/createThread", new JSONObject()).getJSONObject("thread").getString("id");
        open(thread);
        expectDraft(thread, "");
        ClipboardManager clipboard = systemContext().getSystemService(ClipboardManager.class);
        ClipData originalClip = clipboard.getPrimaryClip();
        String sample = "中文输入验证，标点与 Emoji：你好 🌱";
        try {
            click(composer());
            clipboard.setPrimaryClip(ClipData.newPlainText("Taku Unicode test", sample));
            getUiDevice().pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON);
            command("/system/bin/input", "keyevent", "KEYCODE_PASTE");
            expectDraft(thread, sample);
            command("/system/bin/screencap", "-p", "/data/local/tmp/taku-unicode-paste.png");
        } finally {
            if (originalClip == null) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(originalClip);
            writeDraft(thread, "");
            getUiDevice().pressHome();
        }
    }
}
