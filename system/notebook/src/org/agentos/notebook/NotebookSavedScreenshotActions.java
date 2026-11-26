package org.agentos.notebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saved-card actions edit the same entry through Notebook's existing store validation. */
final class NotebookSavedScreenshotActions {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static void open(Activity activity, String savedId, String action, Runnable completed) {
        activity.runOnUiThread(() -> new Request(activity, savedId, completed).open(action));
    }

    private static final class Request {
        private final Activity activity;
        private final String savedId;
        private final Runnable completed;
        private boolean finished;
        private boolean writing;

        Request(Activity activity, String savedId, Runnable completed) {
            this.activity = activity;
            this.savedId = savedId;
            this.completed = completed;
        }

        private boolean alive() {
            return !finished && !activity.isDestroyed() && !activity.isFinishing();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            completed.run();
        }

        void open(String action) {
            if (!alive()) { finish(); return; }
            switch (action) {
                case "go_to_notebook" -> {
                    try {
                        activity.startActivity(NotebookLibraryActivity.detailIntent(activity, savedId));
                    } catch (RuntimeException unavailable) {
                        notice("Couldn’t open Notebook.");
                    }
                    finish();
                }
                case "change_list" -> loadLists();
                case "add_note" -> note();
                default -> { notice("Action unavailable"); finish(); }
            }
        }

        private void loadLists() {
            AlertDialog loading = new AlertDialog.Builder(activity).setTitle("Change List")
                    .setMessage("Loading lists…").setNegativeButton("Cancel", (dialog, which) -> finish())
                    .create();
            loading.setOnCancelListener(dialog -> finish());
            loading.show();
            IO.execute(() -> {
                List<NotebookStore.SavedList> lists = null;
                String current = null;
                try {
                    NotebookStore store = new NotebookStore(activity.getApplicationContext());
                    NotebookStore.Entry entry = store.get(savedId);
                    if (entry == null) throw new IOException("ENTRY_NOT_FOUND");
                    lists = store.listLists();
                    current = entry.listId;
                } catch (IOException | RuntimeException unavailable) {
                    // Report on the UI thread without discarding the saved entry.
                }
                List<NotebookStore.SavedList> choices = lists;
                String selected = current;
                activity.runOnUiThread(() -> {
                    loading.dismiss();
                    if (!alive()) { finish(); return; }
                    if (choices == null || choices.isEmpty()) {
                        notice("Couldn’t load lists.");
                        finish();
                    } else listPicker(choices, selected);
                });
            });
        }

        private void listPicker(List<NotebookStore.SavedList> lists, String current) {
            String[] names = lists.stream().map(list -> list.name).toArray(String[]::new);
            int[] selected = { -1 };
            for (int index = 0; index < lists.size(); index++) {
                if (lists.get(index).id.equals(current)) selected[0] = index;
            }
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Change List")
                    .setSingleChoiceItems(names, selected[0], (choice, index) -> {
                        selected[0] = index;
                        ((AlertDialog) choice).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    })
                    .setNegativeButton("Cancel", (ignored, which) -> finish())
                    .setPositiveButton("Save", null).create();
            dialog.setOnCancelListener(ignored -> finish());
            dialog.setOnShowListener(ignored -> {
                Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                save.setEnabled(selected[0] >= 0);
                save.setOnClickListener(view -> {
                    if (selected[0] >= 0) write(dialog, null, lists.get(selected[0]).id, null);
                });
            });
            dialog.show();
        }

        private void note() {
            EditText field = new EditText(activity);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setMinLines(3);
            field.setMaxLines(6);
            field.setHint("Add a note");
            field.setContentDescription("Screenshot note");
            FrameLayout body = new FrameLayout(activity);
            int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            body.setPadding(padding, 0, padding, 0);
            body.addView(field);
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add note").setView(body)
                    .setNegativeButton("Cancel", (ignored, which) -> finish())
                    .setPositiveButton("Save", null).create();
            dialog.setOnCancelListener(ignored -> finish());
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String text = field.getText().toString().trim();
                    if (text.isEmpty()) field.setError("Enter a note.");
                    else write(dialog, text, null, field);
                });
                field.requestFocus();
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            dialog.show();
        }

        private void write(AlertDialog dialog, String note, String listId, EditText field) {
            if (!alive() || writing) return;
            writing = true;
            editingEnabled(dialog, field, false);
            IO.execute(() -> {
                boolean success;
                try {
                    new NotebookStore(activity.getApplicationContext()).editEntry(savedId, null, note, listId);
                    success = true;
                } catch (IOException | RuntimeException unavailable) {
                    success = false;
                }
                boolean saved = success;
                activity.runOnUiThread(() -> {
                    writing = false;
                    if (!alive()) { dialog.dismiss(); finish(); return; }
                    if (saved) { dialog.dismiss(); finish(); }
                    else {
                        if (field != null) field.setError("Couldn’t save the note. Check its length and retry.");
                        else notice("Couldn’t change the list. Please try again.");
                        editingEnabled(dialog, field, true);
                    }
                });
            });
        }

        private void editingEnabled(AlertDialog dialog, EditText field, boolean enabled) {
            dialog.setCancelable(enabled);
            dialog.setCanceledOnTouchOutside(enabled);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
            View input = field == null ? dialog.getListView() : field;
            if (input != null) input.setEnabled(enabled);
        }

        private void notice(String text) {
            if (!activity.isDestroyed()) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }
    }

    private NotebookSavedScreenshotActions() {}
}
