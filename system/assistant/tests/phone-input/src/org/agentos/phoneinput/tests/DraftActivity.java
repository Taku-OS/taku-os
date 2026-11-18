package org.agentos.phoneinput.tests;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** No network/account code or permissions. All widget state belongs to a visibly synthetic test. */
public final class DraftActivity extends Activity {
    public static final String TITLE = "Taku draft fixture (3)";
    private LinearLayout root;
    private EditText editor;
    private String token, lastState;
    private boolean ready, drawn;
    private int textChangeCount, setTextActionCount, otherEditorActionCount, buttonSendCount, imeActionCount, enterKeyCount;

    @Override public void onCreate(Bundle state) { super.onCreate(state); configure(); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); configure(); }

    private void configure() {
        ready = false; drawn = false; lastState = null;
        textChangeCount = setTextActionCount = otherEditorActionCount = buttonSendCount = imeActionCount = enterKeyCount = 0;
        token = getIntent().getStringExtra("token");
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 253));
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom);
            return insets;
        });
        TextView title = label(TITLE, 24);
        title.setTextColor(Color.BLACK);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        root.addView(label("TEST ONLY · No real chat or account", 14));
        TextView body = label("Synthetic foreground conversation\n\nOnly an unsent draft may be prepared below.\nThe Send button is a counter; it never sends anything.", 18);
        body.setGravity(Gravity.CENTER);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(label("UNSENT TEST DRAFT", 12));
        editor = new EditText(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) setTextActionCount++;
                else otherEditorActionCount++;
                boolean result = super.performAccessibilityAction(action, arguments);
                writeState();
                return result;
            }
        };
        editor.setTextSize(18);
        editor.setMinLines(2); editor.setMaxLines(4);
        editor.setHint("Empty synthetic draft");
        editor.setShowSoftInputOnFocus(false);
        editor.setContentDescription("Synthetic draft composer");
        String initial = getIntent().getStringExtra("draft");
        editor.setText(initial == null ? "" : initial);
        editor.setOnEditorActionListener((view, action, event) -> { imeActionCount++; writeState(); return true; });
        editor.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) { enterKeyCount++; writeState(); }
            return true;
        });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) { textChangeCount++; writeState(); }
        });
        root.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        Button send = new Button(this);
        send.setText("Test Send counter — must stay zero");
        send.setOnClickListener(view -> { buttonSendCount++; writeState(); });
        root.addView(send, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root.getViewTreeObserver().addOnDrawListener(() -> { drawn = true; root.post(this::writeState); });
        root.post(() -> { root.requestFocus(); ready = true; writeState(); });
    }

    private TextView label(String text, int sp) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(sp);
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); writeState(); }

    private void writeState() {
        if (!ready || editor == null) return;
        try {
            Rect bounds = new Rect();
            boolean visible = editor.isShown() && editor.getWindowVisibility() == View.VISIBLE
                    && editor.getGlobalVisibleRect(bounds) && !bounds.isEmpty();
            JSONObject state = new JSONObject().put("synthetic", true).put("title", TITLE).put("token", token)
                    .put("display", getDisplay().getDisplayId()).put("windowFocus", hasWindowFocus()).put("drawn", drawn)
                    .put("visible", visible).put("editorFocused", editor.hasFocus()).put("text", editor.getText().toString())
                    .put("textChangeCount", textChangeCount).put("setTextActionCount", setTextActionCount)
                    .put("otherEditorActionCount", otherEditorActionCount).put("buttonSendCount", buttonSendCount)
                    .put("imeActionCount", imeActionCount).put("enterKeyCount", enterKeyCount);
            String serialized = state.toString();
            if (serialized.equals(lastState)) return;
            File temporary = new File(getFilesDir(), "draft.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(serialized.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!temporary.renameTo(new File(getFilesDir(), "draft.json"))) throw new IllegalStateException("state rename failed");
            lastState = serialized;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
