package org.agentos.assistant;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;

/** Focusable form shown as the input continuation of the dining island. */
public final class DiningPollBookingDetailsActivity extends Activity {
    private static final String[] EDIT_FIELDS = {"date", "time", "zone", "people"};
    private static volatile DiningPollBookingDetailsActivity active;
    private String watchId, decisionKey, revision;
    private DiningPollSchedule schedule;
    private Bundle restoredEdits;
    private LocalDate date;
    private LocalTime time;
    private Button dateButton, timeButton, book;
    private AutoCompleteTextView zone;
    private EditText people;
    private TextView message;
    private DrawerTheme theme;
    private boolean submitting;

    static boolean preserves(String id, String key) {
        DiningPollBookingDetailsActivity current = active;
        return current != null && id.equals(current.watchId) && key.equals(current.decisionKey);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        watchId = getIntent().getStringExtra("watchId");
        decisionKey = getIntent().getStringExtra("decisionKey");
        revision = getIntent().getStringExtra("decisionRevision");
        if (watchId == null || decisionKey == null || revision == null) {
            finish();
            return;
        }
        restoredEdits = state;

        theme = new DrawerTheme(this, true);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(
                view -> {
                    if (!submitting) finish();
                });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = theme.dp(this, 16);
        body.setPadding(pad, pad, pad, pad);
        body.setClickable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(theme.rounded(this, Color.BLACK, 36, false));
        scroll.setClipToOutline(true);
        scroll.addView(body);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int margin = theme.dp(this, 8);
        int width = Math.min(theme.dp(this, 420), screenWidth - 2 * margin);
        root.addView(
                scroll,
                new FrameLayout.LayoutParams(
                        width, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    var bars =
                            insets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout());
                    var ime = insets.getInsets(WindowInsets.Type.ime());
                    root.setPadding(
                            0, bars.top + margin, 0, Math.max(bars.bottom, ime.bottom) + margin);
                    return insets;
                });

        TextView restaurant = text("Booking details", ChatDesign.Role.HEADING);
        body.addView(restaurant);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        body.addView(fields);
        message = text("Loading booking details…", ChatDesign.Role.META);
        body.addView(message);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        body.addView(actions);
        button(
                actions,
                "Cancel",
                () -> {
                    if (!submitting) finish();
                });
        book = button(actions, "Confirm booking", this::submit);
        book.setEnabled(false);
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        setContentView(root);

        DiningPollBooking.execute(
                () -> {
                    try {
                        DiningPollBooking.BookingDetails details =
                                DiningPollBooking.bookingDetailsTarget(
                                        this, watchId, decisionKey, revision);
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    schedule = details.schedule();
                                    if (schedule.complete()) {
                                        restoredEdits = null;
                                        finish();
                                        return;
                                    }
                                    restaurant.setText(
                                            "Book "
                                                    + details.restaurant()
                                                    + "\n"
                                                    + details.address()
                                                    + "?");
                                    showMissingFields(fields);
                                    restoredEdits = null;
                                    message.setText("");
                                    book.setEnabled(true);
                                });
                    } catch (Exception expired) {
                        runOnUiThread(
                                () -> {
                                    if (!isFinishing() && !isDestroyed())
                                        message.setText("This booking prompt has expired.");
                                });
                    }
                });
    }

    private void showMissingFields(LinearLayout fields) {
        date = schedule.date();
        time = schedule.time();
        var confirmed = new ArrayList<String>(4);
        if (date != null) confirmed.add(date.toString());
        if (time != null) confirmed.add(time.toString());
        if (schedule.zone() != null) confirmed.add(schedule.zone().getId());
        if (schedule.people() != null) confirmed.add("Party of " + schedule.people());
        if (!confirmed.isEmpty())
            fields.addView(text(String.join(" · ", confirmed), ChatDesign.Role.META));

        for (DiningPollBookingForm.Field field : DiningPollBookingForm.missing(schedule)) {
            switch (field) {
                case DATE -> {
                    dateButton = button(fields, "Choose date", this::chooseDate);
                    if (restoredEdits != null && restoredEdits.containsKey(field.key))
                        selectDate(LocalDate.parse(restoredEdits.getString(field.key)));
                }
                case TIME -> {
                    timeButton = button(fields, "Choose local time", this::chooseTime);
                    if (restoredEdits != null && restoredEdits.containsKey(field.key))
                        selectTime(LocalTime.parse(restoredEdits.getString(field.key)));
                }
                case ZONE -> showZoneField(fields);
                case PEOPLE -> {
                    fields.addView(text(field.label, ChatDesign.Role.LABEL));
                    people = new EditText(this);
                    people.setHint("Number of guests");
                    people.setInputType(InputType.TYPE_CLASS_NUMBER);
                    people.setSingleLine(true);
                    if (restoredEdits != null)
                        people.setText(restoredEdits.getString(field.key, ""));
                    fields.addView(people);
                }
            }
        }
    }

    private void showZoneField(LinearLayout body) {
        body.addView(text(DiningPollBookingForm.Field.ZONE.label, ChatDesign.Role.LABEL));
        zone = new AutoCompleteTextView(this);
        zone.setSingleLine(true);
        zone.setHint("Search for a city or time zone");
        zone.setThreshold(1);
        String[] zones =
                ZoneId.getAvailableZoneIds().stream()
                        .map(
                                id -> {
                                    int separator = id.lastIndexOf('/');
                                    return separator < 0
                                            ? id
                                            : id.substring(separator + 1).replace('_', ' ')
                                                    + " · "
                                                    + id;
                                })
                        .sorted()
                        .toArray(String[]::new);
        zone.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, zones));
        zone.setOnItemClickListener(
                (parent, view, position, id) -> {
                    String label = (String) parent.getItemAtPosition(position);
                    int separator = label.lastIndexOf(" · ");
                    zone.setText(separator < 0 ? label : label.substring(separator + 3), false);
                });
        if (restoredEdits != null) zone.setText(restoredEdits.getString("zone", ""), false);
        body.addView(zone);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (watchId == null || decisionKey == null) return;
        active = this;
        DiningPollIsland.hide(this, decisionKey);
    }

    @Override
    protected void onStop() {
        if (!submitting && active == this) active = null;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (restoredEdits != null) {
            for (String key : EDIT_FIELDS) {
                if (restoredEdits.containsKey(key))
                    state.putString(key, restoredEdits.getString(key));
            }
            return;
        }
        if (dateButton != null && date != null) state.putString("date", date.toString());
        if (timeButton != null && time != null) state.putString("time", time.toString());
        if (zone != null) state.putString("zone", zone.getText().toString());
        if (people != null) state.putString("people", people.getText().toString());
    }

    private TextView text(String value, ChatDesign.Role role) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(theme.ink);
        ChatDesign.text(view, role);
        return view;
    }

    private Button button(LinearLayout body, String title, Runnable click) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(view -> click.run());
        body.addView(button);
        return button;
    }

    private void chooseDate() {
        ZoneId restaurantZone = schedule.zone();
        LocalDate start =
                date != null
                        ? date
                        : LocalDate.now(
                                restaurantZone != null ? restaurantZone : ZoneId.systemDefault());
        new DatePickerDialog(
                        this,
                        (picker, year, month, day) ->
                                selectDate(LocalDate.of(year, month + 1, day)),
                        start.getYear(),
                        start.getMonthValue() - 1,
                        start.getDayOfMonth())
                .show();
    }

    private void selectDate(LocalDate value) {
        date = value;
        dateButton.setText(value.toString());
    }

    private void chooseTime() {
        ZoneId restaurantZone = schedule.zone();
        LocalTime start =
                time != null
                        ? time
                        : LocalTime.now(
                                restaurantZone != null ? restaurantZone : ZoneId.systemDefault());
        new TimePickerDialog(
                        this,
                        (picker, hour, minute) -> selectTime(LocalTime.of(hour, minute)),
                        start.getHour(),
                        start.getMinute(),
                        android.text.format.DateFormat.is24HourFormat(this))
                .show();
    }

    private void selectTime(LocalTime value) {
        time = value;
        timeButton.setText(value.toString());
    }

    private void submit() {
        if (schedule == null || submitting || !book.isEnabled()) return;
        if (date == null) {
            message.setText("Choose a date.");
            return;
        }
        if (time == null) {
            message.setText("Choose a local time.");
            return;
        }
        var values = new JSONObject();
        try {
            if (dateButton != null) values.put("date", date.toString());
            if (timeButton != null) values.put("time", time.toString());
            if (zone != null) {
                String zoneId = zone.getText().toString().trim();
                if (!ZoneId.getAvailableZoneIds().contains(zoneId)) {
                    message.setText("Select the restaurant’s time zone.");
                    return;
                }
                values.put("zone", zoneId);
            }
            if (people != null) {
                int count = Integer.parseInt(people.getText().toString().trim());
                if (count < 1 || count > 99) throw new NumberFormatException();
                values.put("people", count);
            }
            DiningPollBookingForm.complete(schedule, values, Instant.now());
        } catch (NumberFormatException invalid) {
            message.setText("Enter a party size from 1 to 99.");
            return;
        } catch (Exception invalid) {
            message.setText(formError(invalid));
            return;
        }

        submitting = true;
        book.setEnabled(false);
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(book.getWindowToken(), 0);
        var app = getApplicationContext();
        // The confirmation re-reads the poll in its own WhatsApp window.
        finish();
        DiningPollBooking.execute(
                () -> {
                    try {
                        DiningPollBooking.submitBookingForm(
                                app, watchId, decisionKey, revision, values);
                    } catch (Exception failure) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(app, formError(failure), Toast.LENGTH_LONG)
                                                .show());
                    } finally {
                        if (active == this) active = null;
                    }
                });
    }

    private static String formError(Exception failure) {
        return switch (failure.getMessage() == null ? "" : failure.getMessage()) {
            case "MEAL_TIME_AMBIGUOUS" ->
                    "Choose a time that occurs once in the restaurant’s time zone.";
            case "MEAL_IN_PAST" -> "Choose a booking time in the future.";
            case "INVALID_BOOKING_FIELDS" -> "Check the missing booking details and try again.";
            default -> "Booking could not start. Check the latest poll prompt.";
        };
    }
}
