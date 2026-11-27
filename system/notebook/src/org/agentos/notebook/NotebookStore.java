package org.agentos.notebook;

import android.content.Context;
import android.content.ContentValues;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookStore {
    static final String AUTHORITY = "org.agentos.notebook";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final Uri ENTRIES_URI = URI.buildUpon().appendPath("entries").build();
    static final Uri LISTS_URI = URI.buildUpon().appendPath("lists").build();
    static final String INBOX_ID = "inbox";
    static final String SOURCE_PENDING = "pending";
    static final String SOURCE_LINKED = "linked";
    static final String SOURCE_NOT_FOUND = "not_found";
    static final String SOURCE_UNAVAILABLE = "unavailable";
    static final int MAX_PAGE_SIZE = 50;
    static final List<DefaultList> DEFAULT_LISTS = List.of(
            new DefaultList(INBOX_ID, "Temporary",
                    "Keep saved items here when none of the other lists fit.", "Inbox"),
            new DefaultList("places-to-go", "Places to Go",
                    "Destinations, sights, parks, museums, or outings I want to visit. Put dining spots in Restaurant.", null),
            new DefaultList("restaurants", "Restaurant",
                    "Restaurants, cafés, bakeries, bars, and dishes worth going somewhere to try. Put home recipes in Weekend Recipes.", "Restaurants"),
            new DefaultList("open-source", "Open Source",
                    "Public code repositories, open-source projects, and their libraries, tools, or releases.", "Open source"),
            new DefaultList("wishlist", "Wish List",
                    "Products, gear, or gifts I might buy or want to compare.", "Wishlist"),
            new DefaultList("watch-later", "Watch Later",
                    "Videos, films, shows, talks, and other things I want to watch later.", "Watch later"),
            new DefaultList("photo-studies", "Photo Studies",
                    "Photography references for poses, composition, lighting, viewpoints, or editing.", null),
            new DefaultList("weekend-recipes", "Weekend Recipes",
                    "Recipes, ingredients, and cooking techniques I want to try at home.", null),
            new DefaultList("design-notes", "Design Notes",
                    "Design inspiration for interfaces, interactions, typography, layouts, and visual systems.", null));
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final Pattern MEDIA_IMAGE = Pattern.compile(
            "content://media/[a-zA-Z0-9_-]+/images/media/[1-9][0-9]{0,18}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LIST_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern X_POST_PATH = Pattern.compile(
            "/(?:[A-Za-z0-9_]{1,15}|i/web)/status/[1-9][0-9]{0,19}/?");
    private static final Pattern FILING_EXAMPLE_REFERENCES = Pattern.compile(
            "(?i)\\b(?:[a-z][a-z0-9+.-]*://|mailto:|tel:|data:|www\\.)\\S+|\\b[0-9a-f]{64}\\b");
    private static final Pattern FILING_EXAMPLE_WHITESPACE = Pattern.compile("[\\p{Cc}\\s]+");
    private static final String METADATA = "entry.json";
    private static final String IMAGE = "original";
    private static final String PENDING_PREFIX = ".pending-";
    private static final String TAG = "NotebookStore";
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_LIST_NAME_LENGTH = 100;
    private static final int MAX_RULE_LENGTH = 4000;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    private static final int MAX_FILING_EXAMPLE_LENGTH = 100;
    private static final Object STORE_LOCK = new Object();
    private static File catalogDirectory;
    private static NotebookCatalog sharedCatalog;

    @FunctionalInterface
    private interface CatalogWork<T> {
        T run(NotebookCatalog catalog) throws IOException;
    }

    private final Context context;
    private final File directory;

    public NotebookStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getNoBackupFilesDir(), "notebook");
    }

    record DefaultList(String id, String name, String rule, String legacyName) {}

    public static final class SavedList {
        public final String id;
        public final String name;
        public final String rule;
        public final int count;
        public final boolean system;

        SavedList(String id, String name, String rule, int count) {
            this.id = id;
            this.name = name;
            this.rule = rule;
            this.count = count;
            system = INBOX_ID.equals(id);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("rule", rule)
                    .put("count", count).put("system", system);
        }
    }

    record FilingExample(String title, String summary) {
        FilingExample {
            title = filingExampleText(title);
            summary = filingExampleText(summary);
        }
    }

    record FilingList(String id, String name, String rule, List<FilingExample> examples) {}

    public static final class Entry {
        public final String id;
        public final String title;
        public final String category;
        public final String sourcePackage;
        public final String sourceLabel;
        public final String sourceHandle;
        public final String sourceUrl;
        public final String sourceResolution;
        public final long capturedAt;
        public final Uri imageUri;
        public final String sha256;
        public final String mimeType;
        public final long sizeBytes;
        public final String summary;
        public final String listId;
        public final String description;
        public final long savedAt;
        public final boolean filingPending;
        final boolean titleEdited;
        final boolean listEdited;
        final int sourceAttempts;
        final String sourceZone;
        final String sourceEvidence;

        Entry(String id, String title, String category, String sourcePackage, String sourceLabel,
                String sourceUrl, long capturedAt, String sha256, String mimeType, long sizeBytes,
                String summary, String listId, String description, long savedAt,
                boolean titleEdited, boolean listEdited, String sourceResolution, int sourceAttempts,
                String sourceZone, boolean filingPending, String sourceEvidence) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.sourcePackage = sourcePackage;
            this.sourceLabel = sourceLabel;
            this.sourceUrl = sourceUrl;
            this.sourceResolution = sourceResolution;
            this.capturedAt = capturedAt;
            this.sha256 = sha256;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.summary = summary;
            this.listId = listId;
            this.description = description;
            this.savedAt = savedAt;
            this.filingPending = filingPending;
            this.titleEdited = titleEdited;
            this.listEdited = listEdited;
            this.sourceAttempts = sourceAttempts;
            this.sourceZone = sourceZone;
            this.sourceEvidence = sourceEvidence;
            var platform = NotebookPostEvidence.platform(sourcePackage, sourceUrl);
            String linkedHandle = platform == null ? "" : platform.handleFromPostUrl(sourceUrl);
            sourceHandle = linkedHandle.isEmpty() ? NotebookPostEvidence.handle(sourceEvidence, platform) : linkedHandle;
            imageUri = URI.buildUpon().appendPath("images").appendPath(id).build();
        }

        boolean needsSourceEvidence() {
            return sourceHandle.isEmpty() && !NotebookPostEvidence.accountRead(sourceEvidence)
                    && NotebookPostEvidence.supportsAccount(sourcePackage,
                            NotebookPostEvidence.platform(sourcePackage, sourceUrl));
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("title", title)
                    .put("category", filingPending ? JSONObject.NULL : category)
                    .put("sourcePackage", sourcePackage).put("sourceLabel", sourceLabel)
                    .put("sourceHandle", sourceHandle)
                    .put("sourceUrl", sourceUrl == null ? JSONObject.NULL : sourceUrl)
                    .put("capturedAt", capturedAt).put("imageUri", imageUri.toString())
                    .put("sha256", sha256).put("mimeType", mimeType).put("sizeBytes", sizeBytes)
                    .put("summary", summary).put("listId", filingPending ? JSONObject.NULL : listId)
                    .put("description", description).put("savedAt", savedAt)
                    .put("sourceResolution", sourceResolution).put("filingPending", filingPending);
        }
    }

    public Entry save(Uri original, String title, String category, String sourcePackage,
            String sourceLabel, String sourceUrl, long capturedAt) throws IOException {
        return save(original, title, category, sourcePackage, sourceLabel, sourceUrl, capturedAt, false, "");
    }

    public Entry saveScreenshot(Uri original, String title, String sourcePackage,
            String sourceLabel, String sourceUrl, long capturedAt, String sourceEvidence) throws IOException {
        return save(original, title, INBOX_ID, sourcePackage, sourceLabel, sourceUrl, capturedAt, true,
                sourceEvidence);
    }

    private Entry save(Uri original, String title, String category, String sourcePackage,
            String sourceLabel, String sourceUrl, long capturedAt, boolean filingPending, String sourceEvidence)
            throws IOException {
        if (original == null || !MEDIA_IMAGE.matcher(original.toString()).matches()) {
            throw new IOException("INVALID_SCREENSHOT_URI");
        }

        String id = hex(sha256().digest(original.toString().getBytes(StandardCharsets.UTF_8)));
        return withCatalog(catalog -> {
            Entry previous = catalog.getEntry(id);
            if (previous != null) return previous;
            if (capturedAt <= 0) throw new IOException("INVALID_CAPTURE_TIME");

            File folder = new File(directory, id);
            if (folder.exists()) clearFolder(folder);
            File pending = Files.createTempDirectory(directory.toPath(), PENDING_PREFIX).toFile();
            try {
                SavedList list = resolveCategory(catalog, category);
                long savedAt = System.currentTimeMillis();
                Entry entry = stage(pending, original, id, title, list, sourcePackage, sourceLabel,
                        sourceUrl, capturedAt, savedAt, filingPending, sourceEvidence);
                // Commit the durable image before publishing the entry that references it.
                syncDirectory(pending);
                Os.rename(pending.getPath(), folder.getPath());
                syncDirectory(directory);
                catalog.insertEntry(entry);
                notifyEntryChanges(List.of(entry.id), !filingPending);
                return entry;
            } catch (ErrnoException e) {
                throw new IOException("NOTEBOOK_COMMIT_FAILED", e);
            } finally {
                cleanFolder(pending);
            }
        });
    }

    private Entry stage(File pending, Uri original, String id, String title, SavedList list,
            String sourcePackage, String sourceLabel, String sourceUrl, long capturedAt, long savedAt,
            boolean filingPending, String sourceEvidence) throws IOException {
        File image = new File(pending, IMAGE);
        MessageDigest digest = sha256();
        long size = copyOriginal(original, image, digest);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null
                || !bounds.outMimeType.startsWith("image/")) {
            throw new IOException("INVALID_SCREENSHOT_IMAGE");
        }
        String packageName = label(sourcePackage, 200, "");
        String url = verifiedWebUrl(sourceUrl);
        boolean automatic = NotebookSocialSource.byPackage(packageName) != null;
        return new Entry(id, label(title, MAX_TITLE_LENGTH, "Screenshot"), list.name,
                packageName, label(sourceLabel, 200, ""), url,
                capturedAt, hex(digest.digest()), bounds.outMimeType, size, "", list.id, "", savedAt,
                false, false, automatic ? (url == null ? SOURCE_PENDING : SOURCE_LINKED) : "", 0,
                automatic ? ZoneId.systemDefault().getId() : "", filingPending, automatic && url == null ? sourceEvidence : "");
    }

    boolean enrich(String id, NotebookAnalysis.Identification details, List<FilingList> snapshot)
            throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> {
            Entry entry = catalog.getEntry(id);
            if (entry == null) return false;
            ContentValues values = new ContentValues();
            if (!details.title().isEmpty() && !entry.titleEdited && hasGenericTitle(entry)) {
                values.put("title", label(details.title(), MAX_TITLE_LENGTH, entry.title));
            }
            String filedListId = details.listId();
            boolean filed = entry.filingPending && !entry.listEdited && filedListId != null
                    && snapshot != null && snapshot.stream().anyMatch(list -> filedListId.equals(list.id()))
                    && snapshot.equals(filingLists(catalog, id));
            if (filed) {
                values.put("list_id", filedListId);
                values.put("filing_pending", false);
            }
            if (entry.summary.isEmpty() && !details.summary().isEmpty()) values.put("summary", details.summary());
            if (entry.needsSourceEvidence() && !details.sourceEvidence().isEmpty()) {
                try {
                    values.put("source_evidence", NotebookPostEvidence.withAccountRead(
                            entry.sourceEvidence, details.sourceEvidence()));
                } catch (JSONException invalid) {
                    throw new IOException("INVALID_SOURCE_EVIDENCE", invalid);
                }
            }
            if (values.size() != 0) {
                catalog.updateEntries(List.of(id), values);
                notifyEntryChanges(List.of(id), filed);
            }
            return true;
        });
    }

    private static boolean hasGenericTitle(Entry entry) {
        return "Screenshot".equalsIgnoreCase(entry.title)
                || (!entry.sourceLabel.isEmpty()
                        && (entry.sourceLabel + " screenshot").equalsIgnoreCase(entry.title));
    }

    public Entry attachSource(String id, String url) throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> {
            Entry entry = requireEntry(catalog, id);
            if (entry.sourceUrl != null && !entry.sourceUrl.isBlank()) {
                throw new IOException("SOURCE_ALREADY_SET");
            }
            if (NotebookSocialSource.byPackage(entry.sourcePackage) != null) {
                throw new IOException("SOURCE_AUTOMATIC_ONLY");
            }
            String source = sourceUrlFor(entry, url);
            if (source == null) throw new IOException("INVALID_SOURCE_URL");
            ContentValues values = new ContentValues();
            values.put("source_url", source);
            catalog.updateEntries(List.of(id), values);
            notifyEntryChanges(List.of(id), false);
            return catalog.getEntry(id);
        });
    }

    private static String sourceUrlFor(Entry entry, String value) {
        String url = verifiedWebUrl(value == null ? null : value.strip());
        if (url == null) return null;
        if (!"X".equalsIgnoreCase(entry.sourceLabel)) return url;
        return isXPostUrl(url) ? url : null;
    }

    Entry nextSourceLookup() throws IOException {
        return withCatalog(NotebookCatalog::nextSourceLookup);
    }

    Entry retrySourceLookup(String id) throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> {
            Entry entry = requireEntry(catalog, id);
            if (entry.sourceUrl != null && !entry.sourceUrl.isBlank()) throw new IOException("SOURCE_ALREADY_SET");
            if (NotebookSocialSource.byPackage(entry.sourcePackage) == null) throw new IOException("SOURCE_RETRY_UNAVAILABLE");
            if (SOURCE_PENDING.equals(entry.sourceResolution)) return entry;
            if (!SOURCE_NOT_FOUND.equals(entry.sourceResolution) && !SOURCE_UNAVAILABLE.equals(entry.sourceResolution)) {
                throw new IOException("SOURCE_RETRY_UNAVAILABLE");
            }
            ContentValues values = new ContentValues();
            values.put("source_resolution", SOURCE_PENDING);
            values.put("source_attempts", 0);
            catalog.updateEntries(List.of(id), values);
            notifyEntryChanges(List.of(id), false);
            return catalog.getEntry(id);
        });
    }

    boolean hasPendingSourceLookups() throws IOException {
        return nextSourceLookup() != null;
    }

    boolean updateSourceLookup(Entry lookup, String state, String verifiedUrl) throws IOException {
        return updateSourceLookup(lookup, state, verifiedUrl, null);
    }

    boolean updateSourceLookup(Entry lookup, String state, String verifiedUrl, JSONObject evidence) throws IOException {
        NotebookSocialSource source = NotebookSocialSource.byPackage(lookup.sourcePackage);
        if (source == null) throw new IllegalArgumentException("INVALID_SOURCE_PACKAGE");
        ContentValues values = new ContentValues();
        values.put("source_resolution", state);
        switch (state) {
            case SOURCE_PENDING -> values.put("source_attempts", lookup.sourceAttempts + 1);
            case SOURCE_LINKED -> {
                String url = verifiedWebUrl(verifiedUrl);
                if (url == null || source.canonicalUrl(url) == null) throw new IOException("INVALID_SOURCE_URL");
                values.put("source_url", url);
            }
            case SOURCE_NOT_FOUND, SOURCE_UNAVAILABLE -> { }
            default -> throw new IllegalArgumentException("INVALID_SOURCE_RESOLUTION");
        }
        return withCatalog(catalog -> {
            if (evidence != null) {
                String sourceEvidence = evidence.toString();
                if (!NotebookPostEvidence.handle(sourceEvidence, source).isEmpty()) {
                    Entry current = catalog.getEntry(lookup.id);
                    if (current != null && current.sourceHandle.isEmpty()) {
                        values.put("source_evidence", sourceEvidence);
                    }
                }
            }
            boolean updated = catalog.updateSourceLookup(lookup, values);
            if (updated) notifyEntryChanges(List.of(lookup.id), false);
            return updated;
        });
    }

    static boolean isXPostUrl(String value) {
        String url = verifiedWebUrl(value);
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean xHost = host.equals("x.com") || host.equals("www.x.com") || host.equals("mobile.x.com")
                    || host.equals("twitter.com") || host.equals("www.twitter.com")
                    || host.equals("mobile.twitter.com");
            return "https".equals(uri.getScheme()) && xHost && (uri.getPort() == -1 || uri.getPort() == 443)
                    && X_POST_PATH.matcher(uri.getRawPath()).matches();
        } catch (URISyntaxException failure) {
            return false;
        }
    }

    private void notifyEntryChanges(List<String> ids, boolean listCountsChanged) {
        List<Uri> changed = new ArrayList<>();
        for (String id : ids) changed.add(ENTRIES_URI.buildUpon().appendPath(id).build());
        if (listCountsChanged) changed.add(LISTS_URI);
        context.getContentResolver().notifyChange(changed, null, 0);
    }

    private long copyOriginal(Uri original, File image, MessageDigest digest) throws IOException {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(original, "r");
        if (descriptor == null) throw new FileNotFoundException("SCREENSHOT_UNAVAILABLE");
        long size = 0;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileOutputStream output = new FileOutputStream(image)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_IMAGE_BYTES) throw new IOException("SCREENSHOT_TOO_LARGE");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        return size;
    }

    public List<Entry> list() throws IOException {
        return list(MAX_PAGE_SIZE, 0);
    }

    public List<Entry> list(int limit, int offset) throws IOException {
        requirePage(limit, offset);
        return withCatalog(catalog -> catalog.entries(null, null, null, "e.captured_at DESC",
                limit, offset, null));
    }

    List<Entry> recall(String category, String query, int limit) throws IOException {
        return recall(category, query, List.of(), limit);
    }

    List<Entry> recall(String category, String query, List<String> aliases, int limit) throws IOException {
        String wantedCategory = normalized(category);
        var phrases = new HashSet<String>();
        phrases.add(normalized(query));
        for (String alternative : aliases) phrases.add(normalized(alternative));
        phrases.remove("");
        if (wantedCategory.isEmpty() && phrases.isEmpty()) {
            throw new IllegalArgumentException("RECALL_FILTER_REQUIRED");
        }
        requirePage(limit, 0);
        return withCatalog(catalog -> {
            String alias = defaultListId(wantedCategory);
            var matchingPosts = new HashSet<String>();
            if (!phrases.isEmpty()) {
                var topicMatches = catalog.entries(null, null, null, "e.captured_at DESC", Integer.MAX_VALUE, 0,
                        entry -> NotebookRecall.hasMatchingTopic(entry, phrases));
                for (Entry match : topicMatches) {
                    String key = NotebookRecall.postKey(match);
                    if (key != null) matchingPosts.add(key);
                }
            }
            return catalog.entries(null, null, null, "e.captured_at DESC", limit, 0,
                    entry -> (wantedCategory.isEmpty() || (!entry.filingPending
                            && (wantedCategory.equals(normalized(entry.category)) || entry.listId.equals(alias))))
                            && (phrases.isEmpty()
                            || phrases.stream().anyMatch(phrase -> matchesRecallPhrase(entry, phrase))
                            || NotebookRecall.hasMatchingSource(entry, matchingPosts)));
        });
    }

    static boolean matchesRecallPhrase(Entry entry, String phrase) {
        return phrase.isEmpty() || (!entry.filingPending && entry.category.toLowerCase(Locale.ROOT).contains(phrase))
                || entry.title.toLowerCase(Locale.ROOT).contains(phrase)
                || entry.summary.toLowerCase(Locale.ROOT).contains(phrase)
                || entry.description.toLowerCase(Locale.ROOT).contains(phrase)
                || entry.sourceLabel.toLowerCase(Locale.ROOT).contains(phrase)
                || entry.sourcePackage.toLowerCase(Locale.ROOT).contains(phrase)
                || (!entry.sourceHandle.isEmpty() && ("@" + entry.sourceHandle).toLowerCase(Locale.ROOT).contains(phrase))
                || (entry.sourceUrl != null && entry.sourceUrl.toLowerCase(Locale.ROOT).contains(phrase));
    }

    List<Entry> queryEntries(String query, String source, String listId, Long from, Long to,
            String sort, int limit, int offset) throws IOException {
        requirePage(limit, offset);
        if ((from != null && from < 0) || (to != null && to < 0)
                || (from != null && to != null && from > to)) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }
        String order = switch (sort == null ? "newest" : sort) {
            case "newest" -> "e.saved_at DESC";
            case "oldest" -> "e.saved_at ASC";
            default -> throw new IllegalArgumentException("INVALID_SORT");
        };
        String phrase = normalized(query);
        String sourceFilter = normalized(source);
        if (listId != null) requireListId(listId);
        return withCatalog(catalog -> {
            if (listId != null) requireList(catalog, listId);
            return catalog.entries(listId, from, to, order, limit, offset,
                    phrase.isEmpty() && sourceFilter.isEmpty() ? null
                            : entry -> matchesRecallPhrase(entry, phrase) && matchesSource(entry, sourceFilter));
        });
    }

    private static boolean matchesSource(Entry entry, String source) {
        return source.isEmpty() || source.equals(normalized(entry.sourceLabel))
                || source.equals(normalized(entry.sourcePackage))
                || (entry.sourceUrl != null && normalized(entry.sourceUrl).contains(source));
    }

    private static void requirePage(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("INVALID_PAGE");
        }
    }

    List<Entry> listAll() throws IOException {
        return withCatalog(catalog -> catalog.entries(null, null, null, "e.captured_at DESC",
                Integer.MAX_VALUE, 0, null));
    }

    List<Entry> pendingAnalysisEntries(int limit) throws IOException {
        requirePage(limit, 0);
        return withCatalog(catalog -> catalog.pendingAnalysisEntries(limit));
    }

    public Entry get(String id) throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> catalog.getEntry(id));
    }

    List<SavedList> listLists() throws IOException {
        return withCatalog(NotebookCatalog::lists);
    }

    List<FilingList> filingLists(String excludedEntryId) throws IOException {
        requireEntryId(excludedEntryId);
        return withCatalog(catalog -> filingLists(catalog, excludedEntryId));
    }

    private static List<FilingList> filingLists(NotebookCatalog catalog, String excludedEntryId) {
        List<FilingList> snapshot = new ArrayList<>();
        for (SavedList list : catalog.lists()) {
            snapshot.add(new FilingList(list.id, list.name, list.rule,
                    catalog.filingExamples(list.id, excludedEntryId)));
        }
        return List.copyOf(snapshot);
    }

    SavedList getList(String id) throws IOException {
        requireListId(id);
        return withCatalog(catalog -> catalog.getList(id));
    }

    SavedList createList(String name, String rule) throws IOException {
        String requestedName = checkedText(name, MAX_LIST_NAME_LENGTH, false, "INVALID_LIST_NAME");
        String requestedRule = checkedText(rule == null ? "" : rule, MAX_RULE_LENGTH, true,
                "INVALID_LIST_RULE");
        return withCatalog(catalog -> {
            requireUniqueListName(catalog, requestedName, null);
            String id = "list-" + UUID.randomUUID();
            catalog.insertList(id, requestedName, requestedRule);
            context.getContentResolver().notifyChange(LISTS_URI, null, 0);
            return catalog.getList(id);
        });
    }

    SavedList updateList(String id, String name, String rule) throws IOException {
        requireListId(id);
        if (name == null && rule == null) throw new IllegalArgumentException("EDIT_REQUIRED");
        return withCatalog(catalog -> {
            SavedList current = requireList(catalog, id);
            String nextName = name == null ? current.name
                    : checkedText(name, MAX_LIST_NAME_LENGTH, false, "INVALID_LIST_NAME");
            String nextRule = rule == null ? current.rule
                    : checkedText(rule, MAX_RULE_LENGTH, true, "INVALID_LIST_RULE");
            requireUniqueListName(catalog, nextName, id);
            catalog.updateList(id, nextName, nextRule);
            List<Uri> changed = nextName.equals(current.name) ? List.of(LISTS_URI)
                    : List.of(LISTS_URI, ENTRIES_URI);
            context.getContentResolver().notifyChange(changed, null, 0);
            return catalog.getList(id);
        });
    }

    Entry editEntry(String id, String title, String appendDescription, String listId) throws IOException {
        requireEntryId(id);
        if (title == null && appendDescription == null && listId == null) {
            throw new IllegalArgumentException("EDIT_REQUIRED");
        }
        Entry edited = withCatalog(catalog -> {
            Entry entry = requireEntry(catalog, id);
            ContentValues values = new ContentValues();
            if (title != null) {
                values.put("title", checkedText(title, MAX_TITLE_LENGTH, false, "INVALID_TITLE"));
                values.put("title_edited", true);
            }
            if (appendDescription != null) {
                String appended = checkedText(appendDescription, MAX_DESCRIPTION_LENGTH, false,
                        "INVALID_DESCRIPTION");
                String description = entry.description.isEmpty() ? appended : entry.description + "\n" + appended;
                if (description.length() > MAX_DESCRIPTION_LENGTH) {
                    throw new IllegalArgumentException("DESCRIPTION_TOO_LONG");
                }
                values.put("description", description);
            }
            if (listId != null) putManualList(catalog, listId, values);
            catalog.updateEntries(List.of(id), values);
            notifyEntryChanges(List.of(id), listId != null
                    && (entry.filingPending || !entry.listId.equals(listId)));
            return catalog.getEntry(id);
        });
        if ((title != null || appendDescription != null) && !edited.summary.isEmpty()) {
            NotebookArrivalIntent.prepare(context, edited);
            NotebookArrivalJob.onEntryChanged(context);
        }
        return edited;
    }

    List<Entry> moveEntries(List<String> ids, String listId) throws IOException {
        List<String> batch = requireBatch(ids);
        return withCatalog(catalog -> {
            requireEntries(catalog, batch);
            ContentValues values = new ContentValues();
            putManualList(catalog, listId, values);
            catalog.updateEntries(batch, values);
            notifyEntryChanges(batch, true);
            return catalog.getEntries(batch);
        });
    }

    List<Entry> removeEntries(List<String> ids) throws IOException {
        List<String> batch = requireBatch(ids);
        List<Entry> removed = withCatalog(catalog -> {
            List<Entry> entries = requireEntries(catalog, batch);
            catalog.removeEntries(batch);
            // The catalog commits the whole removal before cleanup of now-unreachable images.
            for (String id : batch) cleanFolder(new File(directory, id));
            notifyEntryChanges(batch, true);
            return entries;
        });
        NotebookArrivalJob.onEntryChanged(context);
        return removed;
    }

    private static void putManualList(NotebookCatalog catalog, String listId, ContentValues values)
            throws IOException {
        requireListId(listId);
        requireList(catalog, listId);
        values.put("list_id", listId);
        values.put("list_edited", true);
        values.put("filing_pending", false);
        values.put("filed_at", System.currentTimeMillis());
    }

    private static List<String> requireBatch(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PAGE_SIZE
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("INVALID_BATCH");
        }
        for (String id : ids) requireEntryId(id);
        return List.copyOf(ids);
    }

    private static List<Entry> requireEntries(NotebookCatalog catalog, List<String> ids) throws IOException {
        List<Entry> entries = catalog.getEntries(ids);
        if (entries.size() != ids.size()) throw new IOException("ENTRY_NOT_FOUND");
        return entries;
    }

    private static Entry requireEntry(NotebookCatalog catalog, String id) throws IOException {
        Entry entry = catalog.getEntry(id);
        if (entry == null) throw new FileNotFoundException("ENTRY_NOT_FOUND");
        return entry;
    }

    private static SavedList requireList(NotebookCatalog catalog, String id) throws IOException {
        SavedList list = catalog.getList(id);
        if (list == null) throw new IOException("LIST_NOT_FOUND");
        return list;
    }

    private static void requireUniqueListName(NotebookCatalog catalog, String name, String id) {
        SavedList match = catalog.listNamed(name);
        if (match != null && !match.id.equals(id)) throw new IllegalArgumentException("LIST_NAME_EXISTS");
    }

    Bitmap previewImage(String id, int maxEdge) throws IOException {
        try (ParcelFileDescriptor file = openImage(id)) {
            var bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, bounds);
            int edge = Math.max(bounds.outWidth, bounds.outHeight);
            if (edge <= 0) return null;
            var sample = new BitmapFactory.Options();
            sample.inSampleSize = 1;
            while ((long) maxEdge * sample.inSampleSize < edge) sample.inSampleSize *= 2;
            return BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, sample);
        }
    }

    ParcelFileDescriptor openImage(String id) throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> {
            requireEntry(catalog, id);
            return openImageFile(id);
        });
    }

    Entry getPoseSource(String id) throws IOException {
        requireEntryId(id);
        return withCatalog(catalog -> {
            Entry entry = requireEntry(catalog, id);
            try (ParcelFileDescriptor image = openImageFile(id)) {
                if (!validId(entry.sha256) || entry.sizeBytes <= 0 || image.getStatSize() != entry.sizeBytes) {
                    throw new IOException("IMAGE_UNAVAILABLE");
                }
            } catch (FileNotFoundException e) {
                throw new IOException("IMAGE_UNAVAILABLE", e);
            }
            return entry;
        });
    }

    private ParcelFileDescriptor openImageFile(String id) throws FileNotFoundException {
        return ParcelFileDescriptor.open(new File(new File(directory, id), IMAGE),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private static void requireEntryId(String id) {
        if (!validId(id)) throw new IllegalArgumentException("INVALID_ENTRY_ID");
    }

    private static void requireListId(String id) {
        if (id == null || !LIST_ID.matcher(id).matches()) throw new IllegalArgumentException("INVALID_LIST_ID");
    }

    static String verifiedWebUrl(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    && uri.getHost() != null && uri.getRawUserInfo() == null) {
                String ascii = uri.toASCIIString();
                return ascii.length() <= 2048
                        ? scheme.toLowerCase(Locale.ROOT) + ascii.substring(scheme.length()) : null;
            }
        } catch (URISyntaxException ignored) {
        }
        return null;
    }

    private static String label(String value, int limit, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String text = value.strip();
        return text.substring(0, Math.min(text.length(), limit));
    }

    private static String filingExampleText(String value) {
        String text = FILING_EXAMPLE_REFERENCES.matcher(value).replaceAll(" ");
        text = FILING_EXAMPLE_WHITESPACE.matcher(text).replaceAll(" ").strip();
        int length = Math.min(text.codePointCount(0, text.length()), MAX_FILING_EXAMPLE_LENGTH);
        return text.substring(0, text.offsetByCodePoints(0, length));
    }

    private static String checkedText(String value, int limit, boolean allowBlank, String error) {
        if (value == null) throw new IllegalArgumentException(error);
        String text = value.strip();
        if ((!allowBlank && text.isEmpty()) || text.length() > limit || text.indexOf(0) >= 0) {
            throw new IllegalArgumentException(error);
        }
        return text;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String defaultListId(String category) {
        for (DefaultList list : DEFAULT_LISTS) {
            if (list.id().equals(category)
                    || (list.legacyName() != null && normalized(list.legacyName()).equals(category))) {
                return list.id();
            }
        }
        return null;
    }

    private static SavedList resolveCategory(NotebookCatalog catalog, String category) {
        String normalizedCategory = normalized(category);
        String defaultId = defaultListId(normalizedCategory);
        SavedList known = defaultId == null ? null : catalog.getList(defaultId);
        if (known == null) known = catalog.listNamed(normalizedCategory);
        return known != null ? known : catalog.getList(INBOX_ID);
    }

    private <T> T withCatalog(CatalogWork<T> work) throws IOException {
        synchronized (STORE_LOCK) {
            try {
                if (!directory.equals(catalogDirectory)) {
                    Files.createDirectories(directory.toPath());
                    NotebookCatalog opened = NotebookCatalog.open(directory, this::importLegacyEntries);
                    if (sharedCatalog != null) sharedCatalog.close();
                    sharedCatalog = opened;
                    catalogDirectory = directory;
                    recoverFolders(opened);
                }
                return work.run(sharedCatalog);
            } catch (SQLiteException failure) {
                throw new IOException("NOTEBOOK_UNAVAILABLE", failure);
            }
        }
    }

    private void importLegacyEntries(NotebookCatalog catalog) throws IOException {
        File[] folders = directory.listFiles(file -> file.isDirectory() && validId(file.getName()));
        if (folders == null) throw new IOException("NOTEBOOK_UNAVAILABLE");
        Arrays.sort(folders, Comparator.comparing(File::getName));
        for (File folder : folders) {
            catalog.insertEntry(readLegacyEntry(catalog, folder));
        }
    }

    private static Entry readLegacyEntry(NotebookCatalog catalog, File folder) throws IOException {
        try {
            byte[] snapshot = Files.readAllBytes(new File(folder, METADATA).toPath());
            JSONObject data = new JSONObject(new String(snapshot, StandardCharsets.UTF_8));
            String id = data.getString("id");
            if (!folder.getName().equals(id)) throw new IOException("INVALID_ENTRY_ID");
            SavedList list = importCategory(catalog, data.getString("category"));
            long capturedAt = data.getLong("capturedAt");
            return new Entry(id, data.getString("title"), list.name, data.getString("sourcePackage"),
                    data.getString("sourceLabel"), data.isNull("sourceUrl") ? null : data.getString("sourceUrl"),
                    capturedAt, data.getString("sha256"), data.getString("mimeType"),
                    data.getLong("sizeBytes"), data.optString("summary"), list.id, "", capturedAt,
                    false, false, "", 0, "", false, "");
        } catch (JSONException e) {
            throw new IOException("NOTEBOOK_METADATA_FAILED", e);
        }
    }

    private static SavedList importCategory(NotebookCatalog catalog, String category) {
        SavedList known = resolveCategory(catalog, category);
        if (!INBOX_ID.equals(known.id) || normalized(category).isEmpty()
                || INBOX_ID.equals(defaultListId(normalized(category)))) return known;
        String id = "legacy-" + hex(sha256().digest(normalized(category).getBytes(StandardCharsets.UTF_8)))
                .substring(0, 48);
        catalog.insertList(id, category.strip(), "");
        return catalog.getList(id);
    }

    private void recoverFolders(NotebookCatalog catalog) {
        File[] folders = directory.listFiles(File::isDirectory);
        if (folders == null) return;
        for (File folder : folders) {
            String id = folder.getName();
            if (id.startsWith(PENDING_PREFIX) || (validId(id) && catalog.getEntry(id) == null)) {
                cleanFolder(folder);
            } else if (validId(id)) {
                try {
                    clearLegacyMetadata(folder);
                } catch (IOException failure) {
                    Log.w(TAG, "Unable to clear migrated metadata", failure);
                }
            }
        }
    }

    private static void clearLegacyMetadata(File folder) throws IOException {
        Files.deleteIfExists(new File(folder, METADATA).toPath());
        Files.deleteIfExists(new File(folder, METADATA + ".tmp").toPath());
    }

    private static void clearFolder(File folder) throws IOException {
        Files.deleteIfExists(new File(folder, IMAGE).toPath());
        clearLegacyMetadata(folder);
        Files.deleteIfExists(folder.toPath());
    }

    private static void cleanFolder(File folder) {
        try {
            clearFolder(folder);
        } catch (IOException failure) {
            Log.w(TAG, "Unable to clear an unreferenced screenshot", failure);
        }
    }

    private static void syncDirectory(File directory) throws ErrnoException {
        FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(fd);
        } finally {
            Os.close(fd);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
