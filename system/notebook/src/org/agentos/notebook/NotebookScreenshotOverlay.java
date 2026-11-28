package org.agentos.notebook;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookScreenshotOverlay {
    record Action(String id, String label) {}
    record ListChoice(String id, String label, boolean selected) {}

    private enum Mode { HIDDEN, READING, STATUS, CARD, COLLAPSED, LISTS, NOTE }

    private record Geometry(int x, int y, int width, int maxHeight, int headerHeight,
                            int eyesLeft, int labelLeft, Rect camera) {}
    private record Frame(int x, int y, int width, int height, float radius) {}

    private static final int FILL = 0xff111214;
    private static final int TEXT = 0xfff7f6f6;
    private static final int MUTED = 0xffacadba;
    private static final int CONTROL = 0xff27282b;
    private static final int ACCENT = 0xffd5cffc;
    private static final int READING_TRAILING_SPACE_DP = 7;
    private static final long CARD_IDLE_MS = 5_000;
    private static final long CAPSULE_LIFETIME_MS = 20_000;
    private static final long MORPH_MS = 190;

    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable cardTimeout = this::advanceCardLifetime;
    private final OnBackInvokedCallback cancelNote = this::cancelEditor;
    private final Runnable blink = this::blink;
    private final GradientDrawable background = new GradientDrawable();
    private Mode mode = Mode.HIDDEN;
    private OverlayView root;
    private WindowManager.LayoutParams params;
    private Frame displayed;
    private Frame target;
    private Geometry geometry;
    private ValueAnimator morph;
    private FrameLayout eyes;
    private LinearLayout content;
    private EditText editor;
    private OnBackInvokedDispatcher backDispatcher;
    private boolean requestKeyboard;
    private String postTitle = "";
    private String message = "";
    private boolean showTitleWithStatus;
    private List<Action> actions = List.of();
    private Consumer<String> onAction;
    private Runnable onExpire;
    private List<ListChoice> choices = List.of();
    private String selectedList;
    private Consumer<String> onSelect;
    private Consumer<String> onSubmit;
    private Runnable onCancel;

    NotebookScreenshotOverlay(AccessibilityService service) {
        Display display = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        context = service.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        windows = context.getSystemService(WindowManager.class);
        background.setColor(FILL);
    }

    void reading() {
        clearEditors();
        postTitle = "";
        actions = List.of();
        onAction = null;
        onExpire = null;
        message = "Reading image";
        show(Mode.READING);
    }

    void status(String text) {
        showTitleWithStatus = mode == Mode.CARD || (mode == Mode.STATUS && showTitleWithStatus);
        message = text;
        clearEditors();
        show(Mode.STATUS);
    }

    void card(String title, List<Action> actions, Consumer<String> onAction, Runnable onExpire) {
        clearEditors();
        postTitle = title;
        this.actions = List.copyOf(actions);
        this.onAction = onAction;
        this.onExpire = onExpire;
        show(Mode.CARD);
    }

    private void collapseCard() {
        if (mode == Mode.CARD) show(Mode.COLLAPSED);
    }

    void lists(List<ListChoice> choices, Consumer<String> onSelect, Runnable onCancel) {
        this.choices = List.copyOf(choices);
        selectedList = choices.stream().filter(ListChoice::selected).map(ListChoice::id).findFirst()
                .orElse(choices.isEmpty() ? null : choices.get(0).id());
        this.onSelect = onSelect;
        this.onCancel = onCancel;
        show(Mode.LISTS);
    }

    void note(Consumer<String> onSubmit, Runnable onCancel) {
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;
        requestKeyboard = true;
        show(Mode.NOTE);
    }

    void hide() {
        main.removeCallbacks(cardTimeout);
        main.removeCallbacks(blink);
        closeKeyboard();
        stopMorph();
        mode = Mode.HIDDEN;
        if (root != null) {
            windows.removeViewImmediate(root);
            root = null;
        }
        displayed = null;
        target = null;
        geometry = null;
        eyes = null;
        content = null;
        editor = null;
        postTitle = "";
        actions = List.of();
        onAction = null;
        onExpire = null;
        clearEditors();
    }

    void configurationChanged() {
        if (root != null) render(false);
    }

    JSONObject snapshot() throws JSONException {
        boolean attached = root != null && root.isAttachedToWindow();
        WindowInsets insets = attached ? root.getRootWindowInsets() : null;
        var result = new JSONObject().put("attached", attached)
                .put("mode", mode.name().toLowerCase(Locale.ROOT))
                .put("keyboard", insets != null && insets.isVisible(WindowInsets.Type.ime()));
        if (displayed != null) {
            result.put("x", displayed.x()).put("y", displayed.y())
                    .put("width", displayed.width()).put("height", displayed.height());
        }
        if (geometry != null && !geometry.camera().isEmpty()) {
            result.put("camera", geometry.camera().flattenToString());
        }
        return result;
    }

    private void clearEditors() {
        choices = List.of();
        selectedList = null;
        onSelect = null;
        onSubmit = null;
        onCancel = null;
    }

    private void show(Mode next) {
        main.removeCallbacks(cardTimeout);
        if (mode == Mode.NOTE && next != Mode.NOTE) closeKeyboard();
        mode = next;
        render(true);
        scheduleCardTimeout();
    }

    private void scheduleCardTimeout() {
        main.removeCallbacks(cardTimeout);
        switch (mode) {
            case CARD -> main.postDelayed(cardTimeout, CARD_IDLE_MS);
            case COLLAPSED -> main.postDelayed(cardTimeout, CAPSULE_LIFETIME_MS);
            default -> {}
        }
    }

    private void advanceCardLifetime() {
        if (mode == Mode.CARD) {
            collapseCard();
        } else if (mode == Mode.COLLAPSED && onExpire != null) {
            Runnable expire = onExpire;
            onExpire = null;
            expire.run();
        }
    }

    private boolean compact() {
        return mode == Mode.READING || mode == Mode.COLLAPSED
                || (mode == Mode.STATUS && !showTitleWithStatus);
    }

    private Geometry position() {
        var metrics = windows.getCurrentWindowMetrics();
        var insets = metrics.getWindowInsets();
        Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
        Rect camera = insets.getDisplayCutout() == null ? new Rect()
                : insets.getDisplayCutout().getBoundingRectTop();
        int displayWidth = metrics.getBounds().width();
        int center = camera.isEmpty() ? displayWidth / 2 : camera.centerX();
        int leftEdge = bars.left + dp(9);
        int rightEdge = displayWidth - bars.right - dp(9);
        int insetTop = Math.max(bars.top, camera.bottom);
        int top = camera.isEmpty() ? insetTop + dp(4) : Math.max(dp(2), camera.centerY() - dp(18));
        int headerHeight = compact() ? dp(36) : Math.max(dp(38), camera.bottom + dp(5) - top);
        if (mode == Mode.NOTE) {
            top = insetTop + dp(5);
            headerHeight = dp(32);
        }
        int width = Math.min(dp(418), rightEdge - leftEdge);
        int left = center - width / 2;
        if (compact()) {
            int leading = (camera.isEmpty() ? center : camera.left) - dp(39);
            int trailing;
            if (mode == Mode.COLLAPSED) {
                trailing = (camera.isEmpty() ? center : camera.right) + dp(36);
            } else {
                int textWidth = (int) Math.ceil(label(message, 13, true).getPaint().measureText(message));
                trailing = (camera.isEmpty() ? leading + dp(38) : camera.right + dp(10))
                        + textWidth + dp(14);
            }
            width = Math.min(trailing - leading, rightEdge - leftEdge);
            left = leading;
        }
        left = Math.max(leftEdge, Math.min(left, rightEdge - width));
        if (mode == Mode.READING) {
            width = Math.min(width + dp(READING_TRAILING_SPACE_DP), rightEdge - left);
        }
        int eyesLeft = dp(13);
        int labelLeft = camera.isEmpty() ? dp(38) : camera.right - left + dp(10);
        int keyboard = mode == Mode.NOTE ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
        int availableHeight = metrics.getBounds().height() - top - Math.max(bars.bottom, keyboard) - dp(6);
        return new Geometry(left, top, Math.max(1, width), Math.max(1, availableHeight),
                headerHeight, eyesLeft, labelLeft, camera);
    }

    private void render(boolean animate) {
        String draft = mode == Mode.NOTE && editor != null ? editor.getText().toString() : "";
        int selectionStart = mode == Mode.NOTE && editor != null ? editor.getSelectionStart() : -1;
        int selectionEnd = mode == Mode.NOTE && editor != null ? editor.getSelectionEnd() : -1;
        boolean preserveKeyboard = mode == Mode.NOTE && editor != null && snapshotKeyboard();
        geometry = position();
        stopMorph();
        main.removeCallbacks(blink);
        editor = null;
        OverlayView view = root == null ? new OverlayView() : root;
        view.removeAllViews();
        view.setOnClickListener(mode == Mode.COLLAPSED ? unused -> show(Mode.CARD) : null);
        view.setClickable(mode == Mode.COLLAPSED);
        view.setContentDescription(mode == Mode.COLLAPSED ? "Open screenshot actions" : null);
        view.setImportantForAccessibility(mode == Mode.COLLAPSED
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content = column();
        content.addView(header(), new LinearLayout.LayoutParams(-1, geometry.headerHeight()));
        if (!compact()) {
            View body = switch (mode) {
                case CARD -> actionCard();
                case STATUS -> statusCard();
                case LISTS -> listEditor();
                case NOTE -> noteEditor(draft, selectionStart, selectionEnd);
                default -> throw new IllegalStateException(mode.name());
            };
            if (mode == Mode.NOTE) {
                var scroll = new ScrollView(context);
                scroll.addView(body);
                content.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            } else {
                content.addView(body, new LinearLayout.LayoutParams(-1, -2));
            }
            if (animate && displayed != null) {
                body.setAlpha(0f);
                body.animate().alpha(1f).setStartDelay(45).setDuration(135).start();
            }
        }
        view.addView(content, new FrameLayout.LayoutParams(geometry.width(), -2));
        target = measureFrame();
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (mode != Mode.NOTE) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (mode == Mode.READING || mode == Mode.STATUS) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else if (mode == Mode.CARD) {
            flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        if (params == null || root == null) {
            params = new WindowManager.LayoutParams(target.width(), target.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle("Notebook screenshot overlay");
        }
        params.flags = flags;
        params.softInputMode = mode == Mode.NOTE
                ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        try {
            if (root == null) {
                setFrame(target);
                windows.addView(view, params);
                root = view;
            } else if (animate && !target.equals(displayed) && ValueAnimator.areAnimatorsEnabled()) {
                Frame from = displayed;
                setFrame(from);
                windows.updateViewLayout(root, params);
                morph = ValueAnimator.ofFloat(0f, 1f);
                morph.setDuration(MORPH_MS);
                morph.setInterpolator(new DecelerateInterpolator());
                morph.addUpdateListener(value -> {
                    float progress = (float) value.getAnimatedValue();
                    updateFrame(new Frame(
                            interpolate(from.x(), target.x(), progress),
                            interpolate(from.y(), target.y(), progress),
                            interpolate(from.width(), target.width(), progress),
                            interpolate(from.height(), target.height(), progress),
                            from.radius() + (target.radius() - from.radius()) * progress));
                });
                morph.start();
            } else {
                updateFrame(target);
            }
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException failure) {
            if (root != null) hide();
            throw failure;
        }
        main.postDelayed(blink, mode == Mode.READING ? 160 : 1_500);
        if (mode == Mode.NOTE) {
            requestKeyboard |= preserveKeyboard;
            editor.requestFocus();
            root.post(this::openKeyboard);
        }
    }

    private Frame measureFrame() {
        content.measure(View.MeasureSpec.makeMeasureSpec(geometry.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(geometry.maxHeight(), View.MeasureSpec.AT_MOST));
        int height = content.getMeasuredHeight();
        return new Frame(geometry.x(), geometry.y(), geometry.width(), height,
                compact() ? height / 2f : dp(25));
    }

    private FrameLayout header() {
        var header = new FrameLayout(context);
        eyes = new FrameLayout(context);
        eyes.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int offset : new int[] {0, 7}) {
            View eye = new View(context);
            eye.setBackground(shape(Color.WHITE, 3));
            var eyeParams = new FrameLayout.LayoutParams(dp(4), dp(10), Gravity.TOP | Gravity.LEFT);
            eyeParams.leftMargin = dp(offset);
            eyes.addView(eye, eyeParams);
        }
        var eyePosition = new FrameLayout.LayoutParams(dp(12), dp(10), Gravity.CENTER_VERTICAL | Gravity.LEFT);
        eyePosition.leftMargin = geometry.eyesLeft();
        header.addView(eyes, eyePosition);
        if (compact() && mode != Mode.COLLAPSED) {
            var label = label(message, 13, true);
            label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            var labelPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL | Gravity.LEFT);
            labelPosition.leftMargin = geometry.labelLeft();
            labelPosition.rightMargin = dp(10);
            header.addView(label, labelPosition);
        }
        if (mode == Mode.LISTS || mode == Mode.NOTE) {
            var cancel = button("Cancel", false);
            cancel.setBackground(ripple(FILL, 15));
            cancel.setTextColor(MUTED);
            cancel.setMinimumHeight(0);
            cancel.setOnClickListener(unused -> cancelEditor());
            var cancelPosition = new FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT);
            cancelPosition.rightMargin = dp(8);
            header.addView(cancel, cancelPosition);
        }
        return header;
    }

    private LinearLayout actionCard() {
        var body = body();
        body.addView(label(postTitle, 15, true), rowParams(0));
        var row = new LinearLayout(context);
        for (var action : actions) {
            var button = button(action.label(), false);
            var buttonSize = new LinearLayout.LayoutParams(0, -2, 1f);
            if (row.getChildCount() != 0) buttonSize.leftMargin = dp(5);
            button.setOnClickListener(unused -> onAction.accept(action.id()));
            row.addView(button, buttonSize);
        }
        body.addView(row, rowParams(7));
        return body;
    }

    private LinearLayout statusCard() {
        var body = body();
        body.addView(label(postTitle, 15, true), rowParams(0));
        var status = label(message, 13, false);
        status.setTextColor(MUTED);
        status.setMinimumHeight(dp(44));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, rowParams(3));
        return body;
    }

    private LinearLayout listEditor() {
        var body = body();
        body.addView(label("Change List", 15, true), rowParams(0));
        var group = new RadioGroup(context);
        var move = button("Move", true);
        for (var choice : choices) {
            var option = new RadioButton(context);
            option.setId(View.generateViewId());
            option.setText(choice.label());
            option.setTextColor(TEXT);
            option.setTextSize(14);
            option.setSingleLine();
            option.setEllipsize(TextUtils.TruncateAt.END);
            option.setMinimumHeight(dp(44));
            option.setPadding(dp(9), 0, dp(9), 0);
            option.setButtonTintList(new ColorStateList(
                    new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, MUTED}));
            option.setBackground(ripple(CONTROL, 12));
            option.setTag(choice.id());
            var choiceSize = new RadioGroup.LayoutParams(-1, -2);
            choiceSize.topMargin = dp(4);
            group.addView(option, choiceSize);
            if (choice.id().equals(selectedList)) group.check(option.getId());
        }
        var scroll = new ScrollView(context);
        scroll.addView(group);
        int rowHeightLimit = Math.max(dp(44), geometry.maxHeight() - geometry.headerHeight() - dp(95));
        body.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.min(Math.min(choices.size(), 4) * dp(48), rowHeightLimit)));
        move.setEnabled(selectedList != null);
        group.setOnCheckedChangeListener((options, checked) -> {
            View current = options.findViewById(checked);
            selectedList = current == null ? null : (String) current.getTag();
            move.setEnabled(selectedList != null);
        });
        move.setOnClickListener(unused -> runEditor(() -> onSelect.accept(selectedList)));
        body.addView(move, rowParams(7));
        return body;
    }

    private LinearLayout noteEditor(String text, int selectionStart, int selectionEnd) {
        var body = body();
        body.addView(label("Add note", 15, true), rowParams(0));
        var input = new EditText(context);
        input.setHint("Write a note…");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setText(text);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(10), dp(9), dp(10), dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setMinLines(2);
        input.setMaxLines(3);
        GradientDrawable inputBackground = shape(CONTROL, 11);
        inputBackground.setStroke(dp(1), ACCENT);
        input.setBackground(inputBackground);
        if (selectionStart >= 0 && selectionEnd >= 0) {
            input.setSelection(Math.min(selectionStart, text.length()), Math.min(selectionEnd, text.length()));
        }
        body.addView(input, rowParams(7));
        var save = button("Save note", true);
        save.setOnClickListener(unused -> runEditor(() -> onSubmit.accept(input.getText().toString())));
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.performClick();
            return true;
        });
        body.addView(save, rowParams(7));
        editor = input;
        return body;
    }

    private void cancelEditor() {
        if (mode == Mode.LISTS || mode == Mode.NOTE) runEditor(onCancel);
    }

    private void runEditor(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            Log.w("NotebookOverlay", "Screenshot action could not finish", failure);
            Toast.makeText(context, "Couldn't finish. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean snapshotKeyboard() {
        WindowInsets insets = root == null ? null : root.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void openKeyboard() {
        if (mode != Mode.NOTE || root == null || !root.hasWindowFocus() || editor == null) return;
        if (backDispatcher == null) {
            backDispatcher = root.findOnBackInvokedDispatcher();
            if (backDispatcher != null) backDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, cancelNote);
        }
        if (!requestKeyboard) return;
        requestKeyboard = false;
        editor.requestFocus();
        var controller = editor.getWindowInsetsController();
        if (controller != null) {
            controller.show(WindowInsets.Type.ime());
        } else {
            context.getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeKeyboard() {
        requestKeyboard = false;
        if (backDispatcher != null) {
            backDispatcher.unregisterOnBackInvokedCallback(cancelNote);
            backDispatcher = null;
        }
        if (editor != null) {
            var controller = editor.getWindowInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
            if (editor.getWindowToken() != null) context.getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
    }

    private void blink() {
        FrameLayout pair = eyes;
        if (pair == null || root == null || !ValueAnimator.areAnimatorsEnabled()) return;
        pair.animate().scaleY(.14f).setDuration(85).withEndAction(() ->
                pair.animate().scaleY(1f).setDuration(125).start()).start();
        main.postDelayed(blink, mode == Mode.READING ? 1_650 : 4_200);
    }

    private void stopMorph() {
        if (morph != null) {
            morph.cancel();
            morph = null;
        }
    }

    private void updateFrame(Frame frame) {
        setFrame(frame);
        windows.updateViewLayout(root, params);
    }

    private void setFrame(Frame frame) {
        displayed = frame;
        params.x = frame.x();
        params.y = frame.y();
        params.width = frame.width();
        params.height = frame.height();
        background.setCornerRadius(frame.radius());
        if (root != null) root.invalidateOutline();
    }

    private static int interpolate(int from, int to, float progress) {
        return from + Math.round((to - from) * progress);
    }

    private LinearLayout column() {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout body() {
        var body = column();
        body.setPadding(dp(11), 0, dp(11), dp(10));
        return body;
    }

    private LinearLayout.LayoutParams rowParams(int topMargin) {
        var result = new LinearLayout.LayoutParams(-1, -2);
        result.topMargin = dp(topMargin);
        return result;
    }

    private TextView label(String text, int size, boolean bold) {
        var label = new TextView(context);
        label.setText(text);
        label.setTextColor(TEXT);
        label.setTextSize(size);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private Button button(String title, boolean primary) {
        var button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setSingleLine();
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(primary ? FILL : TEXT);
        button.setTextSize(13);
        button.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(ripple(primary ? TEXT : CONTROL, 24));
        return button;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33a9a6b7), shape(color, radius), null);
    }

    private GradientDrawable shape(int color, int radius) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int amount) {
        return Math.round(amount * context.getResources().getDisplayMetrics().density);
    }

    private final class OverlayView extends FrameLayout {
        OverlayView() {
            super(context);
            setBackground(background);
            setClipToOutline(true);
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (focused) post(NotebookScreenshotOverlay.this::openKeyboard);
        }

        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            post(() -> {
                if (root != this) return;
                Geometry current = position();
                if (current.equals(geometry)) return;
                if (mode == Mode.NOTE && current.equals(new Geometry(geometry.x(), geometry.y(),
                        geometry.width(), current.maxHeight(), geometry.headerHeight(), geometry.eyesLeft(),
                        geometry.labelLeft(), geometry.camera()))) {
                    geometry = current;
                    stopMorph();
                    target = measureFrame();
                    updateFrame(target);
                } else {
                    render(false);
                }
            });
            return super.onApplyWindowInsets(insets);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (mode == Mode.NOTE && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) cancelEditor();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (mode != Mode.CARD) return super.dispatchTouchEvent(event);

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_OUTSIDE) {
                collapseCard();
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) main.removeCallbacks(cardTimeout);
            boolean handled = super.dispatchTouchEvent(event);
            if (mode == Mode.CARD && (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL || !handled)) {
                scheduleCardTimeout();
            }
            return handled;
        }
    }
}
