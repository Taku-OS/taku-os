package org.agentos.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Login-only account settings for Taku Credits styled with Taku Mobile Design System v0.8.
 */
public final class TakuAccountSettingsActivity extends Activity {
    private static final ExecutorService IO = Executors.newCachedThreadPool();

    private enum ButtonStyle {
        PRIMARY_INK,
        NEUTRAL,
        DANGER_SUBTLE
    }

    private static final class Palette {
        final boolean dark;
        final int wall;
        final int card;
        final int ink;
        final int sub;
        final int inputBg;
        final int line;
        final int danger;
        final int dangerBg;
        final int success;
        final int warn;
        final int buttonHeight;
        final int buttonRadius;
        final int cardRadius;
        final int gutter;
        final int padX;
        final int padY;

        Palette(Context context, boolean dark) {
            this.dark = dark;
            if (dark) {
                wall = Color.parseColor("#141416");
                card = Color.parseColor("#242427");
                ink = Color.parseColor("#F5F5F5");
                sub = Color.parseColor("#A8A8AE");
                inputBg = Color.parseColor("#343438");
                line = Color.parseColor("#38383D");
                danger = Color.parseColor("#FF6B81");
                dangerBg = Color.parseColor("#26CC2A4A");
                success = Color.parseColor("#4FC260");
                warn = Color.parseColor("#FF8757");
            } else {
                wall = ChatDesign.color(context, R.color.taku_wall);
                card = ChatDesign.color(context, R.color.taku_surface);
                ink = ChatDesign.color(context, R.color.taku_ink);
                sub = Color.parseColor("#77777D");
                inputBg = Color.parseColor("#F3F3F5");
                line = Color.parseColor("#E9E9EC");
                danger = ChatDesign.color(context, R.color.taku_error);
                dangerBg = ChatDesign.color(context, R.color.taku_error_surface);
                success = ChatDesign.color(context, R.color.taku_green_600);
                warn = ChatDesign.color(context, R.color.taku_brand);
            }
            buttonHeight = ChatDesign.pixels(context, R.dimen.taku_button_height);
            buttonRadius = ChatDesign.pixels(context, R.dimen.taku_button_radius);
            cardRadius = ChatDesign.pixels(context, R.dimen.taku_radius_2xl);
            gutter = ChatDesign.pixels(context, R.dimen.taku_gutter);
            padX = ChatDesign.pixels(context, R.dimen.taku_mobile_button_pad_x);
            padY = ChatDesign.pixels(context, R.dimen.taku_button_pad_y);
        }
    }

    private Palette palette;
    private JSONObject status;
    private boolean busy;
    private boolean showPasswordForm;
    private boolean observerRegistered;
    private String draftEmail = "";
    private EditText emailField;
    private EditText passwordField;
    private final ContentObserver stateObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange(selfChange, null);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (alive() && !busy) {
                        loadStatus();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TakuAccountTokenServer.ensureStarted(this);
        boolean dark =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        palette = new Palette(this, dark);
        getOnBackInvokedDispatcher()
                .registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Uri uri = Settings.Global.getUriFor(TakuAuthManager.STATE_SETTING);
        if (uri != null && !observerRegistered) {
            getContentResolver().registerContentObserver(uri, false, stateObserver);
            observerRegistered = true;
        }
        loadStatus();
    }

    @Override
    protected void onPause() {
        if (observerRegistered) {
            getContentResolver().unregisterContentObserver(stateObserver);
            observerRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadStatus();
    }

    @Override
    protected void onDestroy() {
        clearPasswordInputs();
        super.onDestroy();
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadStatus() {
        if (busy) return;
        busy = true;
        render();
        IO.execute(() -> {
            JSONObject loaded = null;
            String err = null;
            try {
                loaded = TakuAuthManager.get(getApplicationContext()).status();
            } catch (Exception e) {
                err = describeError(e.getMessage());
            }
            JSONObject finalStatus = loaded;
            String finalErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalStatus != null) status = finalStatus;
                else if (finalErr != null) toast(finalErr);
                render();
            });
        });
    }

    private void render() {
        if (emailField != null) {
            draftEmail = emailField.getText().toString();
        }
        emailField = null;
        passwordField = null;

        LinearLayout root = column();
        root.setBackgroundColor(palette.wall);
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout body = column();
        body.setPadding(palette.gutter, dp(8), palette.gutter, dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout card = buildCard();
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        if (status == null) {
            card.addView(styledText("加载中…", ChatDesign.Role.META, palette.sub));
        } else {
            String authState = status.optString("authState", TakuAuthManager.STATE_SIGNED_OUT);
            if (TakuAuthManager.STATE_LOCKED_BOOT.equals(authState)) {
                renderLockedCard(card);
            } else if (TakuAuthManager.STATE_SIGNED_IN.equals(authState)
                    || TakuAuthManager.STATE_REFRESHING.equals(authState)) {
                renderSignedInCard(card);
            } else if (TakuAuthManager.STATE_EXPIRED.equals(authState)) {
                renderExpiredCard(card);
            } else {
                renderSignedOutCard(card);
            }
        }

        setContentView(root);
        int lightBars =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(palette.dark ? 0 : lightBars, lightBars);
        }
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), palette.gutter, dp(8));

        ImageButton back = new ImageButton(this);
        back.setContentDescription("返回");
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setImageDrawable(ChatDesign.icon(this, R.drawable.ic_taku_chevron_left, palette.ink));
        back.setStateListAnimator(null);
        back.setElevation(0f);
        back.setTranslationZ(0f);
        back.setBackground(iconButtonBackground());
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(palette.buttonHeight, palette.buttonHeight));

        TextView title = styledText("Taku Credits", ChatDesign.Role.HEADING, palette.ink);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = dp(4);
        header.addView(title, titleParams);
        return header;
    }

    private LinearLayout buildCard() {
        LinearLayout card = column();
        card.setPadding(palette.gutter, palette.gutter, palette.gutter, palette.gutter);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.card);
        bg.setCornerRadius(palette.cardRadius);
        card.setBackground(bg);
        card.setElevation(0f);
        card.setStateListAnimator(null);
        return card;
    }

    private void renderLockedCard(LinearLayout card) {
        addTop(card, styledText("解锁设备后可用", ChatDesign.Role.LABEL, palette.ink), 0);
        addTop(card, takuButton("刷新", ButtonStyle.NEUTRAL, !busy, this::loadStatus), 16);
    }

    private void renderSignedOutCard(LinearLayout card) {
        if (!showPasswordForm) {
            addTop(
                    card,
                    takuButton("使用 Google 登录", ButtonStyle.PRIMARY_INK, !busy, this::startGoogleOAuth),
                    0);
            addTop(
                    card,
                    takuButton("邮箱登录", ButtonStyle.NEUTRAL, !busy, () -> {
                        showPasswordForm = true;
                        render();
                    }),
                    12);
            return;
        }

        emailField =
                takuInput(
                        "邮箱",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setSingleLine(true);
        emailField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if (!draftEmail.isEmpty()) {
            emailField.setText(draftEmail);
            emailField.setSelection(draftEmail.length());
        }
        addTop(card, emailField, 0);

        passwordField =
                takuInput(
                        "密码",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setSingleLine(true);
        configureNoSaveInput(passwordField, "Taku Account Password");
        passwordField.setImeOptions(
                EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        addTop(card, passwordField, 10);

        addTop(
                card,
                takuButton(busy ? "登录中…" : "登录", ButtonStyle.PRIMARY_INK, !busy, this::submitPasswordLogin),
                12);

        View divider = new View(this);
        divider.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(16);
        dividerParams.bottomMargin = dp(16);
        card.addView(divider, dividerParams);

        addTop(
                card,
                takuButton("使用 Google 登录", ButtonStyle.NEUTRAL, !busy, this::startGoogleOAuth),
                0);
    }

    private void renderSignedInCard(LinearLayout card) {
        String email = status.optString("email", "已登录");
        boolean registered = status.optBoolean("providerRegistered", false);
        boolean offline = status.optBoolean("transientOffline", false);
        boolean hasBalance = !status.isNull("creditsBalance");
        String balanceLabel =
                hasBalance
                        ? String.format(Locale.ROOT, "%.2f Credits", status.optDouble("creditsBalance", 0.0))
                        : "--";

        addTop(card, styledText(email, ChatDesign.Role.LABEL, palette.ink), 0);
        addTop(card, styledText(balanceLabel, ChatDesign.Role.HEADING, palette.ink), 10);

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(offline ? palette.warn : (registered ? palette.success : palette.sub));
        dot.setBackground(dotShape);
        stateRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        String stateCopy = offline ? "离线" : (registered ? "可用" : "连接中…");
        TextView stateLabel = styledText(stateCopy, ChatDesign.Role.META, palette.sub);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.leftMargin = dp(6);
        stateRow.addView(stateLabel, labelParams);
        addTop(card, stateRow, 8);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button refreshBtn = takuButton(busy ? "刷新中…" : "刷新", ButtonStyle.NEUTRAL, !busy, this::refreshSignedIn);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1f);
        leftParams.rightMargin = dp(6);
        actions.addView(refreshBtn, leftParams);

        Button logoutBtn = takuButton("退出登录", ButtonStyle.DANGER_SUBTLE, !busy, this::confirmLogout);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rightParams.leftMargin = dp(6);
        actions.addView(logoutBtn, rightParams);

        addTop(card, actions, 20);
    }

    private void renderExpiredCard(LinearLayout card) {
        addTop(card, styledText("登录已过期", ChatDesign.Role.LABEL, palette.ink), 0);
        addTop(card, takuButton("重新登录", ButtonStyle.PRIMARY_INK, !busy, this::clearExpiredToLogin), 16);
    }

    private void startGoogleOAuth() {
        if (busy) return;
        busy = true;
        render();
        IO.execute(() -> {
            String url = null;
            String err = null;
            try {
                JSONObject res = TakuAuthManager.get(getApplicationContext()).startGoogleOAuth(null);
                url = res.getString("authorizeUrl");
            } catch (Exception e) {
                err = describeError(e.getMessage());
            }
            String finalUrl = url;
            String finalErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalErr != null) {
                    toast(finalErr);
                    loadStatus();
                    return;
                }
                render();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)));
                } catch (ActivityNotFoundException noBrowser) {
                    IO.execute(() -> {
                        try {
                            TakuAuthManager.get(getApplicationContext()).cancelOAuth();
                        } catch (Exception ignored) {
                        }
                    });
                    toast("未找到可用浏览器");
                }
            });
        });
    }

    private void submitPasswordLogin() {
        if (busy || emailField == null || passwordField == null) return;
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();
        draftEmail = email;
        if (email.isEmpty()) {
            emailField.setError("请输入邮箱");
            return;
        }
        if (password.isEmpty()) {
            passwordField.setError("请输入密码");
            return;
        }
        busy = true;
        clearPasswordInputs();
        render();
        IO.execute(() -> {
            JSONObject next = null;
            String err = null;
            try {
                next = TakuAuthManager.get(getApplicationContext()).loginWithPassword(email, password);
            } catch (Exception e) {
                err = describeError(e.getMessage());
            }
            JSONObject finalNext = next;
            String finalErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalNext != null) {
                    status = finalNext;
                    draftEmail = "";
                    showPasswordForm = false;
                } else if (finalErr != null) {
                    toast(finalErr);
                }
                render();
            });
        });
    }

    private void refreshSignedIn() {
        if (busy) return;
        busy = true;
        render();
        IO.execute(() -> {
            JSONObject next = null;
            String err = null;
            try {
                next = TakuAuthManager.get(getApplicationContext()).refreshAccount();
            } catch (Exception e) {
                err = describeError(e.getMessage());
                try {
                    next = TakuAuthManager.get(getApplicationContext()).status();
                } catch (Exception ignored) {
                }
            }
            JSONObject finalNext = next;
            String finalErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalNext != null) status = finalNext;
                if (finalErr != null) toast(finalErr);
                render();
            });
        });
    }

    private void confirmLogout() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后将停止使用 Taku Credits。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> performLogout())
                .show();
    }

    private void performLogout() {
        if (busy) return;
        busy = true;
        render();
        IO.execute(() -> {
            JSONObject next = null;
            String err = null;
            try {
                next = TakuAuthManager.get(getApplicationContext()).logout();
            } catch (Exception e) {
                err = describeError(e.getMessage());
            }
            JSONObject finalNext = next;
            String finalErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalNext != null) status = finalNext;
                if (finalErr != null) toast(finalErr);
                render();
            });
        });
    }

    private void clearExpiredToLogin() {
        if (busy) return;
        busy = true;
        render();
        IO.execute(() -> {
            JSONObject next = null;
            try {
                TakuAuthManager manager = TakuAuthManager.get(getApplicationContext());
                manager.clearExpired();
                next = manager.status();
            } catch (Exception ignored) {
            }
            JSONObject finalNext = next;
            runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (finalNext != null) status = finalNext;
                render();
            });
        });
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void addTop(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginDp);
        parent.addView(child, params);
    }

    private TextView styledText(String copy, ChatDesign.Role role, int color) {
        TextView view = new TextView(this);
        view.setText(copy);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        ChatDesign.text(view, role);
        return view;
    }

    private EditText takuInput(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(palette.ink);
        field.setHintTextColor(palette.sub);
        field.setInputType(inputType);
        field.setMinHeight(palette.buttonHeight);
        field.setMinimumHeight(palette.buttonHeight);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setIncludeFontPadding(false);
        ChatDesign.text(field, ChatDesign.Role.BODY);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.inputBg);
        bg.setCornerRadius(palette.buttonRadius);
        bg.setStroke(dp(1), palette.line);
        field.setBackground(bg);
        return field;
    }

    private Button takuButton(String label, ButtonStyle style, boolean enabled, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(palette.buttonHeight);
        button.setMinimumHeight(palette.buttonHeight);
        button.setPadding(palette.padX, palette.padY, palette.padX, palette.padY);
        button.setIncludeFontPadding(false);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setTranslationZ(0f);

        int textColor;
        Drawable bg;
        if (!palette.dark && style == ButtonStyle.PRIMARY_INK) {
            textColor = ChatDesign.color(this, R.color.taku_text_inverse);
            bg = getDrawable(R.drawable.taku_button_secondary_bg);
        } else if (!palette.dark && style == ButtonStyle.NEUTRAL) {
            textColor = palette.ink;
            bg = getDrawable(R.drawable.taku_button_neutral_bg);
        } else if (style == ButtonStyle.PRIMARY_INK) {
            textColor = Color.parseColor("#141416");
            bg = flatButtonBg(Color.parseColor("#F5F5F5"), Color.parseColor("#33141416"));
        } else if (style == ButtonStyle.DANGER_SUBTLE) {
            textColor = palette.danger;
            bg = flatButtonBg(palette.dangerBg, Color.parseColor("#33CC2A4A"));
        } else {
            textColor = palette.ink;
            bg = flatButtonBg(palette.inputBg, Color.parseColor("#33FFFFFF"));
        }
        button.setTextColor(textColor);
        ChatDesign.text(button, ChatDesign.Role.LABEL);
        button.setBackground(bg);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private Drawable flatButtonBg(int fillColor, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(fillColor);
        content.setCornerRadius(palette.buttonRadius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private Drawable iconButtonBackground() {
        if (!palette.dark) {
            return getDrawable(R.drawable.taku_icon_button_bg);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#29FFFFFF")), null, mask);
    }

    private void clearPasswordInputs() {
        if (passwordField != null) passwordField.setText("");
    }

    private static void configureNoSaveInput(EditText field, String description) {
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setContentDescription(description);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String describeError(String code) {
        if ("ERR_USER_LOCKED".equals(code)) return "解锁设备后可用";
        if ("ERR_INVALID_CREDENTIALS".equals(code)) return "邮箱或密码错误";
        if ("ERR_TOKEN_EXPIRED".equals(code)) return "登录已过期，请重新登录";
        if ("ERR_TRANSIENT_OFFLINE".equals(code)) return "网络不可用";
        return "暂时无法完成，请稍后重试";
    }
}
