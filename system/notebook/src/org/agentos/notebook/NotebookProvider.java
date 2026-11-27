package org.agentos.notebook;

import android.content.ActivityNotFoundException;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookProvider extends ContentProvider {
    private static final Uri PERMISSIONS = Uri.parse("content://org.agentos.assistant.permissions");
    private static final int MAX_ARGUMENT_LENGTH = 32_768;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_POSE_RECALL_BACKFILL = 3;
    private static final String[] COLUMNS = {
            "id", "title", "category", "sourcePackage", "sourceLabel", "sourceUrl", "capturedAt",
            "imageUri", "sha256", "mimeType", "sizeBytes", "listId", "description", "summary", "savedAt",
            "sourceResolution", "filingPending", "sourceHandle"
    };

    private NotebookStore store;

    @Override
    public boolean onCreate() {
        store = new NotebookStore(getContext());
        NotebookSourceJob.schedule(getContext());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceNotebookAccess();
        int caller = Binder.getCallingUid();
        if ("backgroundSourceUi".equals(method) && caller != Process.ROOT_UID) {
            throw new SecurityException("Notebook background UI requires the system broker");
        }
        if ((arg != null && arg.length() > MAX_ARGUMENT_LENGTH) || (extras != null && !extras.isEmpty())) {
            return error("INVALID_ARGUMENT");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            JSONObject input = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            JSONObject data;
            switch (method) {
                case "list": {
                    requireKeys(input, "limit", "offset");
                    int limit = recallLimit(input);
                    Long pageOffset = nullableLong(input, "offset");
                    if (pageOffset != null && (pageOffset < 0 || pageOffset > Integer.MAX_VALUE)) {
                        return error("INVALID_PAGE");
                    }
                    int offset = pageOffset == null ? 0 : pageOffset.intValue();
                    data = entriesJson(store.list(limit, offset), limit).put("offset", offset);
                    break;
                }
                case "recall": {
                    requireKeys(input, "limit", "category", "query", "queryAliases", "purpose");
                    int limit = recallLimit(input);
                    String category = optionalString(input, "category");
                    String query = optionalString(input, "query");
                    if (input.has("purpose")) {
                        if (!"pose_reference".equals(input.opt("purpose")) || input.has("queryAliases")) {
                            return error("INVALID_ARGUMENT");
                        }
                        data = recallPoseReferences(category, query, limit);
                    } else {
                        data = entriesJson(store.recall(category, query, recallAliases(input), limit), limit);
                    }
                    break;
                }
                case "lists": {
                    requireKeys(input);
                    JSONArray items = new JSONArray();
                    for (NotebookStore.SavedList list : store.listLists()) items.put(list.toJson());
                    data = new JSONObject().put("items", items);
                    break;
                }
                case "createList": {
                    requireKeys(input, "name", "rule");
                    data = store.createList(requireString(input, "name"), nullableString(input, "rule")).toJson();
                    break;
                }
                case "updateList": {
                    requireKeys(input, "id", "name", "rule");
                    if (input.length() < 2) throw new JSONException("No list fields supplied");
                    data = store.updateList(requireString(input, "id"), nullableString(input, "name"),
                            nullableString(input, "rule")).toJson();
                    break;
                }
                case "queryEntries": {
                    requireKeys(input, "query", "source", "listId", "from", "to", "sort", "limit", "offset");
                    int limit = recallLimit(input);
                    Long rawOffset = nullableLong(input, "offset");
                    if (rawOffset != null && (rawOffset < 0 || rawOffset > Integer.MAX_VALUE)) {
                        throw new IllegalArgumentException("INVALID_PAGE");
                    }
                    int offset = rawOffset == null ? 0 : rawOffset.intValue();
                    data = entriesJson(store.queryEntries(nullableString(input, "query"),
                            nullableString(input, "source"), nullableString(input, "listId"),
                            nullableLong(input, "from"), nullableLong(input, "to"), nullableString(input, "sort"),
                            limit, offset), limit).put("offset", offset);
                    break;
                }
                case "editEntry": {
                    requireKeys(input, "id", "title", "appendDescription", "listId");
                    if (input.length() < 2) throw new JSONException("No entry fields supplied");
                    data = store.editEntry(requireString(input, "id"), nullableString(input, "title"),
                            nullableString(input, "appendDescription"), nullableString(input, "listId")).toJson();
                    break;
                }
                case "batchEntries": {
                    requireKeys(input, "ids", "operation", "listId");
                    String operation = requireString(input, "operation");
                    List<String> ids = batchIds(input.getJSONArray("ids"));
                    List<NotebookStore.Entry> changed = switch (operation) {
                        case "move" -> store.moveEntries(ids, requireString(input, "listId"));
                        case "delete" -> {
                            if (input.has("listId")) throw new JSONException("Unexpected delete destination");
                            yield store.removeEntries(ids);
                        }
                        default -> throw new JSONException("Unknown batch operation");
                    };
                    data = entriesJson(changed, ids.size()).put("count", changed.size()).put("operation", operation);
                    break;
                }
                case "openPose": {
                    if (input.length() != 1) {
                        return error("INVALID_ARGUMENT");
                    }
                    NotebookPose.open(getContext(), requireString(input, "id"));
                    data = new JSONObject().put("launched", true);
                    break;
                }
                case "reference": {
                    requireKeys(input, "id");
                    NotebookStore.Entry entry = store.get(requireString(input, "id"));
                    if (entry == null) return error("ENTRY_NOT_FOUND");
                    String viewer = "org.agentos.agent";
                    if (getContext().getPackageManager().checkSignatures(viewer, getContext().getPackageName())
                            != PackageManager.SIGNATURE_MATCH) return error("REFERENCE_VIEWER_UNAVAILABLE");
                    // One read-only image URI, no access to the library or unrelated photos.
                    getContext().grantUriPermission(viewer, entry.imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    data = entry.toJson();
                    break;
                }
                case "get":
                case "copySource":
                case "openSource":
                case "open": {
                    requireKeys(input, "id");
                    NotebookStore.Entry entry = store.get(requireString(input, "id"));
                    if (entry == null) return error("ENTRY_NOT_FOUND");
                    data = entry.toJson();
                    if ("open".equals(method)) {
                        try {
                            getContext().startActivity(NotebookLibraryActivity.detailIntent(getContext(), entry.id));
                        } catch (ActivityNotFoundException | SecurityException e) {
                            return error("NOTEBOOK_OPEN_UNAVAILABLE");
                        }
                    } else if ("openSource".equals(method) || "copySource".equals(method)) {
                        Uri source = NotebookSourceLink.resolve(entry.sourceUrl);
                        if (source == null) return error("NOTEBOOK_SOURCE_UNAVAILABLE");
                        if ("copySource".equals(method)) {
                            NotebookSourceClipboard.copy(getContext(), source);
                        } else if (!NotebookSourceLink.open(getContext(), source, entry.sourcePackage)) {
                            return error("NOTEBOOK_SOURCE_OPEN_UNAVAILABLE");
                        }
                    }
                    break;
                }
                case "getAppearance": {
                    if (input.length() != 0) {
                        return error("INVALID_ARGUMENT");
                    }
                    data = NotebookAppearance.read(getContext());
                    break;
                }
                case "setAppearance": {
                    if (input.length() != 1 || !(input.opt("theme") instanceof String)
                           ) return error("INVALID_ARGUMENT");
                    NotebookAppearance.set(getContext(), input.getString("theme"));
                    data = NotebookAppearance.read(getContext());
                    break;
                }
                case "save": {
                    if (input.length() != 0) {
                        return error("INVALID_ARGUMENT");
                    }
                    NotebookStore.Entry entry = NotebookScreenshotService.savePending();
                    if (entry == null) return error("NO_PENDING_SCREENSHOT");
                    data = entry.toJson();
                    break;
                }
                case "attachSource": {
                    if (input.length() != 2 || !(input.opt("id") instanceof String)
                            || !(input.opt("url") instanceof String)) {
                        return error("INVALID_ARGUMENT");
                    }
                    data = store.attachSource(input.getString("id"), input.getString("url")).toJson();
                    break;
                }
                case "retrySource": {
                    requireKeys(input, "id");
                    NotebookStore.Entry entry = store.retrySourceLookup(requireString(input, "id"));
                    if (entry.summary.isEmpty()) NotebookFilingJob.schedule(getContext());
                    else NotebookSourceJob.schedule(getContext());
                    data = entry.toJson();
                    break;
                }
                case "runAction": {
                    if (input.length() != 1 || !(input.opt("action") instanceof String)
                           ) return error("INVALID_ARGUMENT");
                    String action = input.getString("action");
                    NotebookScreenshotService.runPendingAction(action);
                    data = new JSONObject().put("action", action);
                    break;
                }
                case "status":
                    requireKeys(input);
                    data = NotebookScreenshotService.status(getContext());
                    break;
                case "backgroundSourceUi":
                    data = NotebookScreenshotService.backgroundSourceUi(input);
                    break;
                case "placeReminderStatus": {
                    requireKeys(input);
                    data = NotebookArrivals.state(getContext());
                    break;
                }
                case "setPlaceRemindersEnabled": {
                    requireKeys(input, "enabled");
                    if (!(input.get("enabled") instanceof Boolean enabled)) return error("INVALID_ARGUMENT");
                    data = NotebookArrivals.setEnabled(getContext(), enabled);
                    break;
                }
                case "placeReminderIntent":
                case "refreshPlaceReminderIntent": {
                    requireKeys(input, "id");
                    NotebookStore.Entry entry = store.get(requireString(input, "id"));
                    if (entry == null) return error("ENTRY_NOT_FOUND");
                    NotebookArrivalIntent.Hint hint;
                    if ("refreshPlaceReminderIntent".equals(method)) {
                        try {
                            hint = NotebookArrivalIntent.understand(getContext(), entry);
                        } catch (Exception unavailable) {
                            return error("PLACE_INTENT_UNAVAILABLE");
                        }
                    } else {
                        hint = NotebookArrivalIntent.cached(getContext(), entry);
                    }
                    data = new JSONObject().put("id", entry.id).put("ready", hint != null)
                            .put("resolution", NotebookArrivals.placeStatus(getContext(), entry, hint));
                    if (hint != null) {
                        data.put("trigger", hint.trigger()).put("category", hint.category())
                                .put("placeName", hint.placeName()).put("locality", hint.locality())
                                .put("action", hint.action()).put("reason", hint.reason())
                                .put("poseEligible", hint.poseEligible());
                    }
                    break;
                }
                case "setEnabled": {
                    requireKeys(input, "enabled");
                    Object enabled = input.get("enabled");
                    if (!(enabled instanceof Boolean)) return error("INVALID_ARGUMENT");
                    NotebookScreenshotService.setEnabled(getContext(), (Boolean) enabled);
                    data = NotebookScreenshotService.status(getContext());
                    break;
                }
                default:
                    return error("UNKNOWN_METHOD");
            }
            return result(new JSONObject().put("ok", true).put("result", data));
        } catch (JSONException e) {
            return error("INVALID_ARGUMENT");
        } catch (IOException | IllegalArgumentException e) {
            return error(errorCode(e, "NOTEBOOK_UNAVAILABLE"));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        enforceNotebookAccess();
        if (selection != null || selectionArgs != null || sortOrder != null) {
            throw new IllegalArgumentException("Use list for pagination; selection and sort are unsupported");
        }
        String[] columns = projection == null ? COLUMNS : projection;
        for (String column : columns) {
            if (!List.of(COLUMNS).contains(column)) throw new IllegalArgumentException("Unknown column: " + column);
        }
        try {
            List<NotebookStore.Entry> entries;
            if (NotebookStore.ENTRIES_URI.equals(uri)) {
                entries = store.list();
            } else {
                String id = resourceId(uri, "entries");
                if (id == null) throw new IllegalArgumentException("Unknown Notebook URI");
                NotebookStore.Entry entry = store.get(id);
                entries = entry == null ? List.of() : List.of(entry);
            }
            MatrixCursor cursor = new MatrixCursor(columns, entries.size());
            for (NotebookStore.Entry entry : entries) {
                JSONObject data = entry.toJson();
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : columns) {
                    Object value = data.isNull(column) ? null : data.get(column);
                    row.add(value instanceof Boolean flag ? (flag ? 1 : 0) : value);
                }
            }
            return cursor;
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Notebook unavailable", e);
        }
    }

    @Override
    public String getType(Uri uri) {
        String id = resourceId(uri, "images");
        if (id != null && mayReadImage(uri)) {
            try {
                NotebookStore.Entry entry = store.get(id);
                return entry == null ? null : entry.mimeType;
            } catch (IOException e) {
                return null;
            }
        }
        if (!isNotebookCaller()) return null;
        if (NotebookStore.ENTRIES_URI.equals(uri)) {
            return "vnd.android.cursor.dir/vnd.org.agentos.notebook.entry";
        }
        return resourceId(uri, "entries") == null ? null : "vnd.android.cursor.item/vnd.org.agentos.notebook.entry";
    }

    @Override
    public String getTypeAnonymous(Uri uri) {
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = resourceId(uri, "images");
        if (id == null || !"r".equals(mode)) throw new FileNotFoundException("Image URI must be read only");
        if (!mayReadImage(uri)) throw new SecurityException("Notebook image read grant required");
        try {
            return store.openImage(id);
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException(errorCode(e, "IMAGE_UNAVAILABLE"));
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use save with the pending screenshot");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Use editEntry, updateList, or batchEntries");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Use batchEntries with explicit entry IDs");
    }

    private static String resourceId(Uri uri, String resource) {
        String id = uri.getLastPathSegment();
        if (!NotebookStore.validId(id)) return null;
        Uri expected = NotebookStore.URI.buildUpon().appendPath(resource).appendPath(id).build();
        return expected.equals(uri) ? id : null;
    }

    private boolean mayReadImage(Uri uri) {
        return isNotebookCaller() || getContext().checkUriPermission(uri, Binder.getCallingPid(),
                Binder.getCallingUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceNotebookAccess() {
        if (!isNotebookCaller()) throw new SecurityException("Notebook access denied");
    }

    private boolean isNotebookCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.ROOT_UID || uid == Process.SHELL_UID
                || uid == Process.SYSTEM_UID || getContext().getPackageManager()
                        .checkSignatures(Process.SYSTEM_UID, uid) == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            var request = new JSONObject().put("uid", uid).put("scope", "taku.control");
            var response = getContext().getContentResolver().call(PERMISSIONS, "check", request.toString(), null);
            if (response == null) return false;
            var result = new JSONObject(response.getString("result", "{}"));
            return result.optBoolean("ok") && Boolean.TRUE.equals(result.opt("allowed"));
        } catch (IllegalArgumentException | JSONException | SecurityException unavailable) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static Bundle result(JSONObject data) {
        Bundle bundle = new Bundle();
        bundle.putString("result", data.toString());
        return bundle;
    }

    private JSONObject recallPoseReferences(String category, String query, int limit) throws IOException, JSONException {
        var candidates = store.recall(category, query, NotebookStore.MAX_PAGE_SIZE);
        var items = new JSONArray();
        int queued = 0;
        for (NotebookStore.Entry entry : candidates) {
            var hint = NotebookArrivalIntent.cached(getContext(), entry);
            if (NotebookPose.isAvailable(store, entry, hint)) {
                items.put(entry.toJson().put("poseEligible", true));
                if (items.length() == limit) break;
            } else if (hint == null && queued < MAX_POSE_RECALL_BACKFILL) {
                NotebookArrivalIntent.prepare(getContext(), entry);
                queued++;
            }
        }
        return new JSONObject().put("items", items).put("limit", limit).put("pending", queued > 0);
    }

    private static JSONObject entriesJson(List<NotebookStore.Entry> entries, int limit) throws JSONException {
        JSONArray items = new JSONArray();
        for (NotebookStore.Entry entry : entries) items.put(entry.toJson());
        return new JSONObject().put("items", items).put("limit", limit);
    }

    private static String optionalString(JSONObject input, String key) throws JSONException {
        return input.has(key) ? requireString(input, key) : "";
    }

    private static List<String> recallAliases(JSONObject input) throws JSONException {
        if (!input.has("queryAliases")) return List.of();
        JSONArray values = input.getJSONArray("queryAliases");
        if (values.length() > 3) throw new JSONException("Too many query aliases");
        List<String> aliases = new ArrayList<>(values.length());
        for (int i = 0; i < values.length(); i++) {
            if (!(values.get(i) instanceof String phrase) || phrase.isBlank() || phrase.length() > 160) {
                throw new JSONException("Invalid query alias");
            }
            aliases.add(phrase);
        }
        return aliases;
    }

    private static String nullableString(JSONObject input, String key) throws JSONException {
        return input.has(key) ? requireString(input, key) : null;
    }

    private static String requireString(JSONObject input, String key) throws JSONException {
        if (input.get(key) instanceof String value) return value;
        throw new JSONException("Expected string: " + key);
    }

    private static Long nullableLong(JSONObject input, String key) throws JSONException {
        if (!input.has(key)) return null;
        Object value = input.get(key);
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw new JSONException("Expected integer: " + key);
    }

    private static List<String> batchIds(JSONArray input) throws JSONException {
        if (input.length() < 1 || input.length() > NotebookStore.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("INVALID_BATCH_SIZE");
        }
        List<String> ids = new ArrayList<>(input.length());
        for (int i = 0; i < input.length(); i++) {
            if (!(input.get(i) instanceof String id)) throw new JSONException("Expected entry ID");
            ids.add(id);
        }
        return ids;
    }

    private static void requireKeys(JSONObject input, String... allowed) throws JSONException {
        Set<String> keys = Set.of(allowed);
        for (var iterator = input.keys(); iterator.hasNext();) {
            if (!keys.contains(iterator.next())) throw new JSONException("Unexpected Notebook argument");
        }
    }

    private static int recallLimit(JSONObject input) throws JSONException {
        if (!input.has("limit")) return DEFAULT_LIMIT;
        Object value = input.get("limit");
        if (!(value instanceof Number)) throw new JSONException("Expected numeric limit");
        double limit = ((Number) value).doubleValue();
        if (limit < 1 || limit > NotebookStore.MAX_PAGE_SIZE) throw new IllegalArgumentException("INVALID_PAGE");
        if (limit != (int) limit) throw new JSONException("Expected integer limit");
        return (int) limit;
    }

    private static Bundle error(String code) {
        try {
            return result(new JSONObject().put("ok", false).put("error", code));
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String errorCode(Exception e, String fallback) {
        String message = e.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,80}") ? message : fallback;
    }
}
