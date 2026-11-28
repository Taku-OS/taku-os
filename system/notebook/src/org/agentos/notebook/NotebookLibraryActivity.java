package org.agentos.notebook;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.agentos.ai.AiClient;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookLibraryActivity extends Activity {
    private static final String EXTRA_ENTRY_ID = "org.agentos.notebook.entry_id";
    private static final String EXTRA_SAVED_ACTION = "org.agentos.notebook.saved_action";
    private static final String EXTRA_SETTINGS = "org.agentos.notebook.settings";
    private static final int PHOTOS_PERMISSION = 1;
    private static final int REMINDER_PERMISSIONS = 61;
    private static final String HOST = "notebook.invalid";
    private static final String LIBRARY_URL = "https://" + HOST + "/library.html";
    private static final Map<String, String> ASSET_TYPES = Map.of(
            "/library.html", "text/html", "/library.css", "text/css",
            "/library.js", "text/javascript", "/hanken-grotesk.woff2", "font/woff2",
            "/space-grotesk.woff2", "font/woff2");
    private static final Set<String> MUTATIONS = Set.of("createList", "updateList", "editEntry", "batchEntries", "retrySource");
    private static final Set<String> SAVED_ACTIONS = Set.of("go_to_notebook", "change_list", "add_note");
    private static final long LIBRARY_REFRESH_DELAY_MS = 200;

    private enum SourceAction { OPEN, COPY, ATTACH }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NotebookPlaceReminderSettings reminderSettings = new NotebookPlaceReminderSettings(this);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshLibrary = () -> {
        if (this.ready && !isDestroyed()) refresh();
    };
    private final OnBackInvokedCallback back = () -> evaluate("back()", result -> {
        if (!"true".equals(result)) finish();
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceChanged =
            (preferences, key) -> applyAppearance();
    private final ContentObserver libraryChanges = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            if (!ready || isDestroyed() || main.hasCallbacks(refreshLibrary)) return;
            main.postDelayed(refreshLibrary, LIBRARY_REFRESH_DELAY_MS);
        }
    };

    private NotebookStore store;
    private WebView web;
    private boolean ready;
    private String viewState;
    private String pendingEntryId;
    private String pendingSavedAction;
    private boolean pendingSettings;
    private boolean settingsRead;
    private boolean openingSettings;
    private int pendingPermission;
    private String insets = "{}";
    private int refreshVersion;

    static Intent detailIntent(Context context, String id) {
        return libraryIntent(context).putExtra(EXTRA_ENTRY_ID, id);
    }

    static Intent savedActionIntent(Context context, String entryId, String action) {
        return detailIntent(context, entryId).putExtra(EXTRA_SAVED_ACTION, action);
    }

    static Intent settingsIntent(Context context) {
        return libraryIntent(context).putExtra(EXTRA_SETTINGS, true);
    }

    private static Intent libraryIntent(Context context) {
        return new Intent(context, NotebookLibraryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new NotebookStore(this);
        if (savedInstanceState == null) {
            readDestination(getIntent());
        } else {
            viewState = savedInstanceState.getString("view");
            pendingEntryId = savedInstanceState.getString("pendingEntryId");
            pendingSavedAction = savedInstanceState.getString("pendingSavedAction");
            pendingSettings = savedInstanceState.getBoolean("pendingSettings");
            pendingPermission = savedInstanceState.getInt("pendingPermission");
        }

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setNavigationBarContrastEnforced(false);
        web = new WebView(this);
        web.setSaveEnabled(false);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setAlgorithmicDarkeningAllowed(false);
        web.addJavascriptInterface(new Commands(), "Notebook");
        web.setWebViewClient(new LocalLibrary());
        web.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            updateInsets(windowInsets);
            return windowInsets;
        });
        setContentView(web);
        applyAppearance();
        web.requestApplyInsets();
        web.loadUrl(LIBRARY_URL);

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
        NotebookAppearance.preferences(this).registerOnSharedPreferenceChangeListener(appearanceChanged);
        getContentResolver().registerContentObserver(NotebookStore.ENTRIES_URI, true, libraryChanges);
        getContentResolver().registerContentObserver(NotebookStore.LISTS_URI, true, libraryChanges);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NotebookScreenshotService.refreshPermissions();
        NotebookArrivalJob.resume(this);
        worker.execute(() -> NotebookSourceJob.resumeAfterLeavingSource(this));
        openingSettings = false;
        if (ready) refresh();
        if (settingsRead) readSettings();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readDestination(intent);
        if (ready) refresh();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != pendingPermission) return;
        pendingPermission = 0;
        if (request == PHOTOS_PERMISSION) NotebookScreenshotService.refreshPermissions();
        if (request == REMINDER_PERMISSIONS) setRemindersEnabled(true);
        else if (request == PHOTOS_PERMISSION && !NotebookScreenshotService.canReadImages(this)) {
            notice(label("Allow photo access in Android settings to use screenshot actions.",
                    "请在 Android 设置中允许照片访问，以使用截图操作。"));
        }
        publishSettings();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("view", viewState);
        state.putString("pendingEntryId", pendingEntryId);
        state.putString("pendingSavedAction", pendingSavedAction);
        state.putBoolean("pendingSettings", pendingSettings);
        state.putInt("pendingPermission", pendingPermission);
    }

    @Override
    protected void onDestroy() {
        NotebookAppearance.preferences(this).unregisterOnSharedPreferenceChangeListener(appearanceChanged);
        getContentResolver().unregisterContentObserver(libraryChanges);
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        main.removeCallbacks(refreshLibrary);
        worker.shutdownNow();
        web.removeJavascriptInterface("Notebook");
        if (web.getParent() instanceof ViewGroup parent) parent.removeView(web);
        web.destroy();
        ready = false;
        super.onDestroy();
    }

    private void readDestination(Intent intent) {
        boolean internal = new ComponentName(this, NotebookLibraryActivity.class).equals(intent.getComponent());
        String id = internal ? intent.getStringExtra(EXTRA_ENTRY_ID) : null;
        String action = internal ? intent.getStringExtra(EXTRA_SAVED_ACTION) : null;
        pendingEntryId = NotebookStore.validId(id) ? id : null;
        pendingSavedAction = pendingEntryId != null && action != null && SAVED_ACTIONS.contains(action)
                ? action : null;
        pendingSettings = internal && intent.getBooleanExtra(EXTRA_SETTINGS, false);
    }

    private void updateInsets(WindowInsets windowInsets) {
        float density = getResources().getDisplayMetrics().density;
        Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
        float bottom = bars.bottom / density;
        insets = "{top:" + bars.top / density + ",bottom:" + bottom
                + ",left:" + bars.left / density + ",right:" + bars.right / density
                + ",keyboard:" + keyboard.bottom / density + "}";
        evaluate("insets(" + insets + ")", null);
    }

    private void applyAppearance() {
        String theme = NotebookAppearance.effectiveTheme(this);
        boolean dark = "dark".equals(theme);
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
        web.setBackgroundColor(dark ? 0xff101114 : 0xffc6d9f0);
        evaluate("theme(" + JSONObject.quote(theme) + ")", null);
        publishSettings();
    }

    private void refresh() {
        main.removeCallbacks(refreshLibrary);
        int version = ++refreshVersion;
        worker.execute(() -> {
            List<NotebookStore.Entry> saved = List.of();
            List<NotebookStore.SavedList> lists = List.of();
            String error = null;
            try {
                saved = store.listAll();
                lists = store.listLists();
                if (saved.stream().anyMatch(NotebookStore.Entry::needsSourceEvidence)) {
                    NotebookFilingJob.resume(this);
                }
            } catch (IOException e) {
                error = "Couldn’t load Notebook.";
            }
            boolean loaded = error == null;
            String model = libraryModel(saved, lists, error);
            runOnUiThread(() -> {
                if (isDestroyed() || version != refreshVersion) return;
                String entryId = null;
                String savedAction = null;
                if (loaded || pendingSavedAction == null) {
                    entryId = pendingEntryId;
                    savedAction = pendingSavedAction;
                    pendingEntryId = null;
                    pendingSavedAction = null;
                }
                boolean showSettings = pendingSettings;
                pendingSettings = false;
                evaluate("update(" + model + "," + JSONObject.quote(viewState) + ","
                        + JSONObject.quote(entryId) + "," + showSettings + ","
                        + JSONObject.quote(savedAction) + ")", null);
            });
        });
    }

    private String label(String english, String chinese) {
        return chineseLocale() ? chinese : english;
    }

    private boolean chineseLocale() {
        return "zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private void readSettings() {
        if (isDestroyed() || isFinishing()) return;
        settingsRead = true;
        publishSettings();
    }

    private void publishSettings() {
        if (!settingsRead || isDestroyed()) return;
        try {
            JSONObject screenshots = NotebookScreenshotService.status(this);
            var model = new JSONObject().put("language", chineseLocale() ? "zh" : "en")
                    .put("appearance", NotebookAppearance.read(this).getString("theme"))
                    .put("screenshotsEnabled", screenshots.getBoolean("enabled"))
                    .put("photoAccess", screenshots.getBoolean("imageAccess"))
                    .put("accessibilityConnected", screenshots.getBoolean("accessibilityConnected"));
            reminderSettings.addStatus(model);
            evaluate("settings(" + model + ")", null);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void changeSetting(String action) {
        if (action == null || !settingsRead || isDestroyed() || isFinishing()
                || pendingPermission != 0 || openingSettings) return;
        switch (action) {
            case "toggle-screen-observer" -> {
                boolean enabled = NotebookScreenshotService.settings(this).getBoolean("enabled", false);
                NotebookScreenshotService.setEnabled(this, !enabled);
            }
            case "request-photos" -> {
                if (!NotebookScreenshotService.canReadImages(this)) {
                    requestSetupPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, PHOTOS_PERMISSION);
                }
            }
            case "open-accessibility" -> openAndroidSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            case "open-ai-settings" -> {
                var ai = new AiClient(this);
                worker.execute(() -> {
                    try {
                        ai.settings(); // Register Notebook for app permissions in Taku AI.
                    } catch (Exception unavailable) {
                        // The settings page handles service errors independently.
                    }
                });
                openAndroidSettings(new Intent("org.agentos.action.AI_SETTINGS")
                        .setPackage("org.agentos.agent"));
            }
            case "enable-reminders" -> {
                String[] permissions = reminderSettings.requiredPermissions();
                if (permissions.length == 0) setRemindersEnabled(true);
                else requestSetupPermissions(permissions, REMINDER_PERMISSIONS);
            }
            case "disable-reminders" -> setRemindersEnabled(false);
            case "open-reminder-android-settings" -> openAndroidSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            case "open-location-services" -> openAndroidSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            default -> { return; }
        }
        publishSettings();
    }

    private void requestSetupPermissions(String[] permissions, int request) {
        pendingPermission = request;
        requestPermissions(permissions, request);
    }

    private void setRemindersEnabled(boolean enabled) {
        String failure = reminderSettings.setEnabled(enabled);
        if (failure != null) notice(failure);
    }

    private void openAndroidSettings(Intent intent) {
        try {
            startActivity(intent);
            openingSettings = true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            notice(label("Couldn’t open settings.", "暂时无法打开设置。"));
        }
    }

    private String libraryModel(List<NotebookStore.Entry> saved, List<NotebookStore.SavedList> lists, String error) {
        JSONArray items = new JSONArray();
        try {
            for (NotebookStore.Entry entry : saved) {
                String source = entry.sourceLabel.isBlank() ? entry.sourcePackage : entry.sourceLabel;
                String sourceKey = entry.sourcePackage.isBlank() ? entry.sourceLabel : entry.sourcePackage;
                var platform = NotebookSocialSource.byPackage(entry.sourcePackage);
                Uri url = NotebookSourceLink.resolve(entry.sourceUrl);
                items.put(new JSONObject().put("id", entry.id).put("title", entry.title)
                        .put("category", entry.category).put("listId", entry.listId)
                        .put("filingPending", entry.filingPending)
                        .put("source", source.isBlank() ? "Unknown source" : source)
                        .put("sourceHandle", entry.sourceHandle)
                        .put("sourceKey", sourceKey).put("savedAt", entry.savedAt)
                        .put("summary", entry.summary).put("description", entry.description)
                        .put("automaticSource", platform == null ? "" : platform.displayName())
                        .put("sourceResolution", entry.sourceResolution)
                        .put("sourceUrl", url == null ? "" : url.toString()));
            }
            JSONArray folders = new JSONArray();
            for (NotebookStore.SavedList list : lists) folders.put(list.toJson());
            return new JSONObject().put("entries", items).put("lists", folders).put("error", error).toString();
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private JSONObject providerCall(String method, String arguments) throws IOException, JSONException {
        Bundle response = getContentResolver().call(NotebookStore.URI, method, arguments, null);
        String result = response == null ? null : response.getString("result");
        if (result == null) throw new IOException("NOTEBOOK_UNAVAILABLE");
        return new JSONObject(result);
    }

    private void searchEntries(String arguments, int request) {
        JSONArray ids = new JSONArray();
        boolean failed = true;
        try {
            JSONObject filters = new JSONObject(arguments);
            int pageSize = NotebookStore.MAX_PAGE_SIZE;
            filters.put("limit", pageSize);
            int offset = 0;
            while (!Thread.currentThread().isInterrupted()) {
                filters.put("offset", offset);
                JSONObject response = providerCall("queryEntries", filters.toString());
                if (!response.optBoolean("ok")) break;
                JSONArray page = response.getJSONObject("result").getJSONArray("items");
                for (int i = 0; i < page.length(); i++) ids.put(page.getJSONObject(i).getString("id"));
                if (page.length() < pageSize) {
                    failed = false;
                    break;
                }
                offset += pageSize;
            }
        } catch (IOException | JSONException | SecurityException ignored) {
            // The search screen displays a retry action.
        }
        boolean searchFailed = failed;
        runOnUiThread(() -> evaluate("searchResult(" + request + "," + ids + "," + searchFailed + ")", null));
    }

    private void saveMutation(String method, String arguments) {
        String error = "NOTEBOOK_UNAVAILABLE";
        if (method != null && MUTATIONS.contains(method)) {
            try {
                JSONObject response = providerCall(method, arguments);
                error = response.optBoolean("ok") ? "" : response.optString("error", error);
            } catch (IOException | JSONException | SecurityException ignored) {
                // Keep the form open so the user can retry the change.
            }
        }
        String result = error;
        runOnUiThread(() -> evaluate("mutationResult(" + JSONObject.quote(result) + ")", null));
    }

    private void evaluate(String call, ValueCallback<String> result) {
        if (ready && !isDestroyed()) web.evaluateJavascript("window.notebook." + call, result);
        else if (result != null) result.onReceiveValue("false");
    }

    private void sourceAction(String id, SourceAction action) {
        if (!NotebookStore.validId(id)) return;
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            worker.execute(() -> loadSourceAction(id, action));
        });
    }

    private void loadSourceAction(String id, SourceAction action) {
        try {
            NotebookStore.Entry entry = store.get(id);
            if (entry == null) return;
            runOnUiThread(() -> showSourceAction(entry, action));
        } catch (IOException e) {
            runOnUiThread(() -> notice("Couldn’t load the source link."));
        }
    }

    private void showSourceAction(NotebookStore.Entry entry, SourceAction action) {
        if (isDestroyed() || isFinishing()) return;
        Uri url = NotebookSourceLink.resolve(entry.sourceUrl);
        if (action == SourceAction.ATTACH) {
            if (url == null && NotebookSocialSource.byPackage(entry.sourcePackage) == null) addSource(entry);
            else refresh();
        } else if (url == null) {
            notice("No link was available from the source app.");
        } else if (action == SourceAction.COPY) {
            NotebookSourceClipboard.copy(this, url);
        } else if (!NotebookSourceLink.open(this, url, entry.sourcePackage)) {
            notice("Couldn’t open the source link.");
        }
    }

    private void addSource(NotebookStore.Entry entry) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine();
        field.setHint("https://");
        field.setContentDescription("Original post link");
        FrameLayout body = new FrameLayout(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, 0, padding, 0);
        body.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add source link")
                .setMessage("Copy the original post’s link from its Share menu, then paste it here.")
                .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(view -> {
                String url = field.getText().toString();
                save.setEnabled(false);
                worker.execute(() -> {
                    String error = null;
                    try {
                        store.attachSource(entry.id, url);
                    } catch (IOException failure) {
                        error = switch (Objects.toString(failure.getMessage(), "")) {
                            case "INVALID_SOURCE_URL" -> "Paste a link to the original post.";
                            case "SOURCE_ALREADY_SET" -> "A source link is already saved.";
                            default -> "Couldn’t save the source link.";
                        };
                    }
                    String message = error;
                    runOnUiThread(() -> {
                        if (isDestroyed() || !dialog.isShowing()) return;
                        if (message == null) {
                            dialog.dismiss();
                        } else {
                            field.setError(message);
                            save.setEnabled(true);
                        }
                    });
                });
            });
        });
        dialog.show();
    }

    private void notice(String message) {
        if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void loadPoseAction(String id, int request) {
        boolean available = false;
        String placeStatus = "";
        try {
            NotebookStore.Entry entry = store.get(id);
            if (entry != null) {
                var hint = NotebookArrivalIntent.cached(this, entry);
                available = NotebookPose.isAvailable(store, entry, hint);
                placeStatus = NotebookArrivals.placeStatus(this, entry, hint).getString("status");
                if (hint == null) NotebookArrivalIntent.prepare(this, entry);
            }
        } catch (IOException | JSONException ignored) {
            // The saved item may have been removed while its detail page was opening.
        }
        boolean show = available;
        String status = placeStatus;
        runOnUiThread(() -> evaluate("detailActions(" + request + "," + JSONObject.quote(id) + "," + show
                + "," + JSONObject.quote(status) + ")", null));
    }

    private void openPose(String id) {
        try {
            NotebookPose.open(this, id);
        } catch (IOException e) {
            boolean chinese = "zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
            String message = switch (Objects.toString(e.getMessage(), "")) {
                case "CAMERA_OPEN_UNAVAILABLE" -> chinese ? "无法打开相机。" : "Couldn’t open the camera.";
                case "ENTRY_NOT_FOUND", "IMAGE_UNAVAILABLE" -> chinese
                        ? "收藏的参考图已不可用。" : "The saved reference image is unavailable.";
                case "POSE_NOT_ELIGIBLE", "POSE_ELIGIBILITY_UNAVAILABLE" -> chinese
                        ? "这条收藏暂时无法用作姿势参考。" : "This save is unavailable as a pose reference.";
                default -> chinese ? "无法加载这张参考图。" : "Couldn’t load this reference image.";
            };
            runOnUiThread(() -> notice(message));
        }
    }

    private final class Commands {
        @JavascriptInterface public void ready() {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                ready = true;
                evaluate("insets(" + insets + ")", null);
                applyAppearance();
                refresh();
            });
        }

        @JavascriptInterface public void saveView(String state) {
            runOnUiThread(() -> viewState = state);
        }

        @JavascriptInterface public void search(String arguments, int request) {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                worker.execute(() -> searchEntries(arguments, request));
            });
        }

        @JavascriptInterface public void mutate(String method, String arguments) {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                worker.execute(() -> saveMutation(method, arguments));
            });
        }

        @JavascriptInterface public void setTheme(String theme) {
            runOnUiThread(() -> {
                if (theme == null || isDestroyed()) return;
                try {
                    NotebookAppearance.set(NotebookLibraryActivity.this, theme);
                } catch (IOException e) {
                    notice(label("Couldn’t change Notebook appearance.", "暂时无法更改 Notebook 外观。"));
                    publishSettings();
                }
            });
        }

        @JavascriptInterface public void openSettings() {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                pendingSettings = true;
                if (ready) refresh();
            });
        }

        @JavascriptInterface public void readSettings() {
            runOnUiThread(NotebookLibraryActivity.this::readSettings);
        }

        @JavascriptInterface public void changeSetting(String action) {
            runOnUiThread(() -> NotebookLibraryActivity.this.changeSetting(action));
        }

        @JavascriptInterface public void openPose(String id) {
            if (!NotebookStore.validId(id)) return;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                worker.execute(() -> NotebookLibraryActivity.this.openPose(id));
            });
        }

        @JavascriptInterface public void loadPoseAction(String id, int request) {
            if (!NotebookStore.validId(id)) return;
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing()) {
                    worker.execute(() -> NotebookLibraryActivity.this.loadPoseAction(id, request));
                }
            });
        }

        @JavascriptInterface public void openSource(String id) { sourceAction(id, SourceAction.OPEN); }
        @JavascriptInterface public void copySource(String id) { sourceAction(id, SourceAction.COPY); }
        @JavascriptInterface public void attachSource(String id) { sourceAction(id, SourceAction.ATTACH); }
    }

    private final class LocalLibrary extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())
                    || !"GET".equals(request.getMethod())) return missingResource();
            String path = uri.getPath();
            String type = ASSET_TYPES.get(path);
            try {
                if (type != null) {
                    return new WebResourceResponse(type, type.startsWith("font/") ? null : "UTF-8",
                            getAssets().open("library" + path));
                }
                List<String> segments = uri.getPathSegments();
                if (segments.size() == 3 && "image".equals(segments.get(0))
                        && NotebookStore.validId(segments.get(2))) {
                    return image(segments.get(1), segments.get(2));
                }
            } catch (IOException e) {
                return missingResource();
            }
            return missingResource();
        }

        private WebResourceResponse image(String variant, String id) throws IOException {
            if ("original".equals(variant)) {
                NotebookStore.Entry entry = store.get(id);
                if (entry != null && entry.mimeType.startsWith("image/")) {
                    return new WebResourceResponse(entry.mimeType, null,
                            new ParcelFileDescriptor.AutoCloseInputStream(store.openImage(id)));
                }
                return missingResource();
            }
            int maxEdge = switch (variant) {
                case "thumb" -> 288;
                case "cover" -> 960;
                default -> 0;
            };
            if (maxEdge == 0) return missingResource();
            Bitmap preview = store.previewImage(id, maxEdge);
            if (preview == null) return missingResource();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try {
                preview.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, data);
            } finally {
                preview.recycle();
            }
            return new WebResourceResponse("image/webp", null, new ByteArrayInputStream(data.toByteArray()));
        }

        private WebResourceResponse missingResource() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Map.of(),
                    new ByteArrayInputStream(new byte[0]));
        }
    }
}
