/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.agentos.inputmethod.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.*;
import android.view.inputmethod.BaseInputConnection;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Synthetic text only. The state is read from the real EditText after IME events. */
public final class InputActivity extends Activity {
    private EditText editor;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(1);
        page.setPadding(32, 80, 32, 20);
        TextView title = new TextView(this);
        title.setText("Taku 输入法 · 键盘验收");
        title.setTextSize(24);
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText("本地测试输入框");
        page.addView(hint);
        editor = new EditText(this);
        editor.setId(1001);
        editor.setContentDescription("Taku test editor");
        editor.setTextSize(24);
        editor.setMinLines(2);
        String mode = getIntent().getStringExtra("mode");
        if ("number".equals(mode)) editor.setInputType(InputType.TYPE_CLASS_NUMBER);
        else if ("password".equals(mode)) editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        page.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable text) { saveState(); }
        });
        setContentView(page);
        editor.requestFocus();
        saveState();
    }
    private void saveState() {
        try {
            Editable text = editor.getText();
            JSONObject state = new JSONObject().put("text", text.toString())
                .put("composingStart", BaseInputConnection.getComposingSpanStart(text))
                .put("composingEnd", BaseInputConnection.getComposingSpanEnd(text));
            Files.write(new File(getFilesDir(), "state.json").toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
