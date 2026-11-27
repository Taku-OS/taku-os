package org.agentos.assistant;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Actual local input: no model turn or SDK question exists while the user fills this screen. */
public final class DiningRideInputActivity extends Activity {
    private static volatile DiningRideInputActivity visibleForm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String watchId, key;
    private EditText pickup, departure;
    private TextView status;
    private Button submit, cancel, choose;
    private boolean loading = true, busy, finished, inputCommitted;
    private Runnable pendingSave;
    private DrawerTheme theme;

    static boolean isShowing() {
        return visibleForm != null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        visibleForm = this;
        DiningBookingRideIsland.hide(this);
    }

    @Override
    protected void onStop() {
        if (visibleForm == this) visibleForm = null;
        super.onStop();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        watchId = getIntent().getStringExtra("watchId");
        key = getIntent().getStringExtra("inputKey");
        theme =
                new DrawerTheme(
                        this,
                        (getResources().getConfiguration().uiMode
                                        & Configuration.UI_MODE_NIGHT_MASK)
                                == Configuration.UI_MODE_NIGHT_YES);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.surface);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = theme.dp(this, 24);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    android.graphics.Insets bars =
                            insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    body.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
                    return insets;
                });
        body.addView(text("Prepare a ride to dinner", ChatDesign.Role.HEADING));
        TextView context = text("Loading your saved reservation…", ChatDesign.Role.BODY);
        body.addView(context);
        body.addView(text("Pickup address", ChatDesign.Role.LABEL));
        pickup =
                field(
                        "Pickup address",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        body.addView(pickup);
        body.addView(text("Departure time", ChatDesign.Role.LABEL));
        departure =
                field(
                        "YYYY-MM-DDTHH:mm",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(departure);
        choose = new Button(this);
        choose.setText("Choose departure time");
        choose.setOnClickListener(v -> chooseTime());
        body.addView(choose);
        status =
                text(
                        "Your reservation and calendar event stay saved. This only prepares a ride;"
                            + " place the order yourself in Uber.",
                        ChatDesign.Role.META);
        body.addView(status);
        submit = new Button(this);
        submit.setText("Prepare ride");
        submit.setOnClickListener(v -> submit());
        body.addView(submit);
        cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> cancel());
        body.addView(cancel);
        setContentView(scroll);
        setBusy(true);
        DiningBookingRide.IO.execute(
                () -> {
                    try {
                        JSONObject form = call("read", new JSONObject());
                        main.post(
                                () -> {
                                    if (isFinishing()) return;
                                    context.setText(
                                            form.optString("restaurant")
                                                    + "\nDeparture uses "
                                                    + form.optString("zone")
                                                    + ".");
                                    ChatDesign.text(context, ChatDesign.Role.BODY);
                                    pickup.setText(form.optString("pickup"));
                                    departure.setText(form.optString("departureLocal"));
                                    inputCommitted = "submitted".equals(form.optString("status"));
                                    if (!inputCommitted
                                            && state != null
                                            && key.equals(state.getString("inputKey"))) {
                                        pickup.setText(
                                                state.getString(
                                                        "pickup", pickup.getText().toString()));
                                        departure.setText(
                                                state.getString(
                                                        "departureLocal",
                                                        departure.getText().toString()));
                                    }
                                    cancel.setText(inputCommitted ? "Back" : "Cancel");
                                    loading = false;
                                    setBusy(false);
                                    watchDraft(pickup);
                                    watchDraft(departure);
                                });
                    } catch (Exception failure) {
                        main.post(
                                () -> {
                                    status.setText(message(failure));
                                    finished = true;
                                    submit.setEnabled(false);
                                    cancel.setEnabled(true);
                                });
                    }
                });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (!java.util.Objects.equals(key, intent.getStringExtra("inputKey"))) {
            setIntent(intent);
            recreate();
        }
    }

    private TextView text(String value, ChatDesign.Role role) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(theme.ink);
        ChatDesign.text(view, role);
        view.setPadding(0, theme.dp(this, 10), 0, theme.dp(this, 10));
        return view;
    }

    private EditText field(String hint, int type) {
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        view.setHint(hint);
        view.setContentDescription(hint);
        view.setSingleLine(true);
        view.setInputType(type);
        view.setTextColor(theme.ink);
        view.setHintTextColor(theme.muted);
        ChatDesign.text(view, ChatDesign.Role.BODY);
        return view;
    }

    private void watchDraft(EditText field) {
        field.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (loading || busy || finished) return;
                        if (pendingSave != null) main.removeCallbacks(pendingSave);
                        pendingSave = () -> saveDraft();
                        main.postDelayed(pendingSave, 300);
                    }

                    public void afterTextChanged(Editable text) {}
                });
    }

    private JSONObject values() throws Exception {
        return new JSONObject()
                .put("pickup", pickup.getText().toString())
                .put("departureLocal", departure.getText().toString());
    }

    private JSONObject call(String action, JSONObject values) throws Exception {
        return DiningBookingRide.call(
                this,
                "dining/poll/ride-input-" + action,
                values.put("id", watchId).put("key", key));
    }

    private void saveDraft() {
        if (loading || finished || busy || inputCommitted) return;
        try {
            JSONObject values = values();
            DiningBookingRide.IO.execute(
                    () -> {
                        try {
                            call("save", values);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        pickup.setEnabled(!value && !inputCommitted);
        departure.setEnabled(!value && !inputCommitted);
        choose.setEnabled(!value && !inputCommitted);
        submit.setEnabled(!value);
        cancel.setEnabled(!value);
    }

    private void submit() {
        if (busy || finished || loading) return;
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        try {
            JSONObject values = values();
            setBusy(true);
            status.setText("Saving your plan…");
            DiningBookingRide.IO.execute(
                    () -> {
                        try {
                            JSONObject result = call("submit", values);
                            String phase = result.optString("phase");
                            main.post(
                                    () -> {
                                        inputCommitted = true;
                                        cancel.setText("Back");
                                        if (Set.of("queued", "running", "awaiting_review", "ended")
                                                .contains(phase)) {
                                            finished = true;
                                            finish();
                                        } else {
                                            setBusy(false);
                                            status.setText(
                                                    result.optString(
                                                            "error",
                                                            "Preparation has not started. Your plan"
                                                                + " is saved."));
                                        }
                                    });
                        } catch (Exception failure) {
                            boolean committed = false;
                            try {
                                committed =
                                        "submitted"
                                                .equals(
                                                        call("read", new JSONObject())
                                                                .optString("status"));
                            } catch (Exception ignored) {
                            }
                            final boolean saved = committed;
                            main.post(
                                    () -> {
                                        inputCommitted = saved;
                                        cancel.setText(saved ? "Back" : "Cancel");
                                        setBusy(false);
                                        status.setText(message(failure));
                                    });
                        }
                    });
        } catch (Exception failure) {
            setBusy(false);
            status.setText(message(failure));
        }
    }

    private void cancel() {
        if (finished || inputCommitted) {
            finish();
            return;
        }
        if (busy) return;
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        try {
            JSONObject values = values();
            setBusy(true);
            DiningBookingRide.IO.execute(
                    () -> {
                        try {
                            call("save", values);
                            call("cancel", new JSONObject());
                            main.post(
                                    () -> {
                                        finished = true;
                                        finish();
                                    });
                        } catch (Exception failure) {
                            main.post(
                                    () -> {
                                        setBusy(false);
                                        status.setText(message(failure));
                                    });
                        }
                    });
        } catch (Exception failure) {
            status.setText(message(failure));
        }
    }

    @Override
    public void onBackPressed() {
        if (!busy) {
            saveDraft();
            finish();
        }
    }

    @Override
    protected void onPause() {
        saveDraft();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("inputKey", key);
        out.putString("pickup", pickup.getText().toString());
        out.putString("departureLocal", departure.getText().toString());
    }

    private void chooseTime() {
        LocalDateTime value;
        try {
            value = LocalDateTime.parse(departure.getText().toString());
        } catch (Exception invalid) {
            status.setText("Enter a departure in YYYY-MM-DDTHH:mm first.");
            return;
        }
        final int hour = value.getHour(), minute = value.getMinute();
        new DatePickerDialog(
                        this,
                        (picker, year, month, day) ->
                                new TimePickerDialog(
                                                this,
                                                (clock, h, m) ->
                                                        departure.setText(
                                                                LocalDateTime.of(
                                                                                year, month + 1,
                                                                                day, h, m)
                                                                        .format(
                                                                                DateTimeFormatter
                                                                                        .ofPattern(
                                                                                                "yyyy-MM-dd'T'HH:mm"))),
                                                hour,
                                                minute,
                                                true)
                                        .show(),
                        value.getYear(),
                        value.getMonthValue() - 1,
                        value.getDayOfMonth())
                .show();
    }

    private String message(Exception error) {
        return switch (String.valueOf(error.getMessage())) {
            case "DINING_RIDE_PICKUP_REQUIRED" -> "Enter a pickup address.";
            case "DINING_RIDE_TIME_INVALID", "DINING_RIDE_TIME_AMBIGUOUS" ->
                    "Choose an unambiguous restaurant-local departure at least 30 minutes from now"
                        + " and before dinner.";
            case "DINING_PHONE_BUSY", "THREAD_BUSY", "THREAD_QUEUED" ->
                    "Another task is active. Your plan is saved; continue when it is idle.";
            case "REQUEST_EXPIRED", "DINING_CALENDAR_EVENT_CHANGED" ->
                    "This form has expired. Reopen the current dinner plan.";
            default ->
                    "Ride preparation could not continue. Reopen the dinner plan to check its"
                        + " status.";
        };
    }
}
