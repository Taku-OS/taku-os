package org.agentos.phoneinput.tests;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic input surfaces only. State comes from real widgets, independently of accessibility. */
public class InputFixtureActivity extends Activity {
    private final ArrayList<EditText> editors = new ArrayList<>();
    private LinearLayout root;
    private Dialog dialog;
    private String mode, token;
    private String lastState;
    private boolean ready, drawn;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        configure();
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        configure();
    }

    private void configure() {
        ready = false;
        drawn = false;
        lastState = null;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        editors.clear();
        mode = getIntent().getStringExtra("mode");
        token = getIntent().getStringExtra("token");
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);
        root.setFocusableInTouchMode(true);
        root.setContentDescription("fixture focus holder");
        TextView title = new TextView(this);
        title.setText("Taku input fixture: " + mode);
        root.addView(title);
        boolean front = this instanceof ForegroundActivity;
        addEditor(root, front ? "前台保留 🌿" : "后台甲");
        if ("focused".equals(mode) || "ambiguous".equals(mode)) addEditor(root, "后台乙");
        if ("filtered".equals(mode)) editors.get(0).setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        setContentView(root);
        root.getViewTreeObserver().addOnDrawListener(() -> {
            drawn = true;
            root.post(this::writeState);
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root.post(() -> {
            if (front) editors.get(0).requestFocus();
            else if ("focused".equals(mode)) editors.get(1).requestFocus();
            else root.requestFocus();
            if ("two-windows".equals(mode)) {
                dialog = new Dialog(this);
                LinearLayout other = new LinearLayout(this);
                other.setPadding(16, 16, 16, 16);
                other.setFocusableInTouchMode(true);
                addEditor(other, "另一窗口");
                dialog.setContentView(other);
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog.getWindow().setGravity(Gravity.BOTTOM);
                dialog.show();
                other.requestFocus();
                other.getViewTreeObserver().addOnDrawListener(() -> other.post(this::writeState));
            }
            ready = true;
            writeState();
        });
    }

    private void addEditor(LinearLayout parent, String text) {
        EditText editor = new EditText(this);
        editor.setContentDescription("fixture editor " + editors.size());
        editor.setText(text);
        editor.setShowSoftInputOnFocus(false);
        editor.setMinLines(2);
        editors.add(editor);
        parent.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        editor.setOnFocusChangeListener((view, focused) -> writeState());
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable text) { writeState(); }
        });
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        writeState();
    }

    private void writeState() {
        if (!ready) return;
        try {
            JSONArray texts = new JSONArray(), focus = new JSONArray(), visible = new JSONArray();
            for (EditText editor : editors) {
                texts.put(editor.getText().toString());
                focus.put(editor.hasFocus());
                Rect bounds = new Rect();
                visible.put(editor.isShown() && editor.getWindowVisibility() == View.VISIBLE
                        && editor.getGlobalVisibleRect(bounds) && !bounds.isEmpty());
            }
            JSONObject state = new JSONObject().put("display", getDisplay().getDisplayId())
                    .put("mode", mode).put("token", token).put("texts", texts).put("focused", focus)
                    .put("windowFocus", hasWindowFocus()).put("drawn", drawn).put("visible", visible);
            String serialized = state.toString();
            if (serialized.equals(lastState)) return;
            String name = this instanceof ForegroundActivity ? "front.json" : "back.json";
            File temporary = new File(getFilesDir(), name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(serialized.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.renameTo(new File(getFilesDir(), name))) throw new IllegalStateException("state rename failed");
            lastState = serialized;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
