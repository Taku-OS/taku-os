package org.agentos.generated.menu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MenuData {
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 36 * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_PIXELS = 90_000_000;
    private static final String IMAGE_PATH = "images/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.(?:jpg|jpeg|png|webp)";

    public static void main(String[] args) {
        try {
            require(args.length == 2, "Pass the input JSON and application project");
            prepare(new File(args[0]), new File(args[1]).toPath());
        } catch (IOException | JSONException | IllegalArgumentException error) {
            System.err.println("Menu validation failed: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void prepare(File input, Path project) throws IOException, JSONException {
        Path source = input.toPath().toAbsolutePath();
        byte[] bytes = readBounded(source, MAX_JSON_BYTES);
        String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject menu;
        try (JsonReader parser = new JsonReader(new StringReader(document))) {
            menu = readObject(parser, 0);
            require(parser.peek() == JsonToken.END_DOCUMENT, "Unexpected content after menu");
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException("Malformed JSON", ex);
        }
        Images images = new Images(source.getParent(), project);
        validateMenu(menu, images);
        JSONArray dishes = menu.getJSONArray("dishes");
        PostCandidates.enrich(source.resolveSibling("post-candidates.json"), menu.getJSONObject("venue"), dishes);
        List<JSONObject> missingArtwork = new ArrayList<>();
        for (int index = 0; index < dishes.length(); index++) {
            JSONObject dish = dishes.getJSONObject(index);
            if (dish.getString("image").isEmpty()) missingArtwork.add(dish);
        }
        MenuDishArtwork.generate(missingArtwork, images::saveArtwork);
        images.writeGeneratedCover(dishes);
        Files.write(project.resolve("assets/menu-data.json"),
                menu.toString().getBytes(StandardCharsets.UTF_8));
        writeLabel(project, menu.getJSONObject("venue").getString("name"));
    }

    private static void writeLabel(Path project, String name) throws IOException {
        String label = hasVisibleText(name) ? name : "Menu";
        String escaped = label.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        Path values = project.resolve("res/values");
        Files.createDirectories(values);
        Files.write(values.resolve("strings.xml"),
                ("<resources><string name=\"menu_app_name\" formatted=\"false\">\""
                        + escaped + "\"</string></resources>").getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject readObject(JsonReader parser, int depth) throws IOException, JSONException {
        require(depth <= 8, "JSON is nested too deeply");
        JSONObject object = new JSONObject();
        parser.beginObject();
        while (parser.hasNext()) {
            String key = parser.nextName();
            require(object.length() < 16, "Too many object fields");
            require(!object.has(key), "Duplicate object fields");
            object.put(key, readValue(parser, depth + 1));
        }
        parser.endObject();
        return object;
    }

    private static JSONArray readArray(JsonReader parser, int depth) throws IOException, JSONException {
        require(depth <= 8, "JSON is nested too deeply");
        JSONArray array = new JSONArray();
        parser.beginArray();
        while (parser.hasNext()) {
            require(array.length() < 100, "Too many items");
            array.put(readValue(parser, depth + 1));
        }
        parser.endArray();
        return array;
    }

    private static Object readValue(JsonReader parser, int depth) throws IOException, JSONException {
        switch (parser.peek()) {
            case BEGIN_OBJECT:
                return readObject(parser, depth);
            case BEGIN_ARRAY:
                return readArray(parser, depth);
            case STRING:
                return parser.nextString();
            case NUMBER:
                String number = parser.nextString();
                require(number.matches("-?(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,6})?"),
                        "Numbers must use decimal notation with up to six fractional digits");
                return new BigDecimal(number);
            case BOOLEAN:
                return parser.nextBoolean();
            case NULL:
                parser.nextNull();
                return JSONObject.NULL;
            default:
                throw new IllegalArgumentException("Unexpected JSON value");
        }
    }

    private static void validateMenu(JSONObject menu, Images images) throws IOException, JSONException {
        fields(menu, "venue", "currency", "dishes");
        JSONObject venue = menu.getJSONObject("venue");
        fields(venue, "name", "address", "placeId");
        fieldText(venue, "name", 160, true);
        fieldText(venue, "address", 300, true);
        String place = fieldText(venue, "placeId", 256, true);
        String currency = fieldText(menu, "currency", 3, false);
        require(currency.matches("[A-Z]{3}"), "Currency must use an ISO 4217 code");
        require(Currency.getInstance(currency).getDefaultFractionDigits() >= 0,
                "Currency must have a minor unit");

        JSONArray dishes = menu.getJSONArray("dishes");
        require(dishes.length() > 0, "The menu must include at least one dish");
        Set<String> dishIds = new HashSet<>();
        for (int index = 0; index < dishes.length(); index++) {
            JSONObject dish = dishes.getJSONObject(index);
            fields(dish, "id", "name", "priceMinor", "kcal", "carbs", "fat", "protein",
                    "ingredients", "image", "recommendations");
            String dishId = fieldText(dish, "id", 80, false);
            require(dishId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"), "Invalid dish ID");
            require(dishIds.add(dishId), "Dish IDs must be unique");
            fieldText(dish, "name", 160, false);
            Object price = dish.get("priceMinor");
            require(price instanceof BigDecimal && ((BigDecimal) price).scale() == 0,
                    "priceMinor must be an integer");
            validateNumber(price, "priceMinor", 999_999_999);
            validateNutrition(dish, "kcal", 20_000);
            validateNutrition(dish, "carbs", 5_000);
            validateNutrition(dish, "fat", 5_000);
            validateNutrition(dish, "protein", 5_000);
            JSONArray ingredients = dish.getJSONArray("ingredients");
            require(ingredients.length() <= 40, "Too many ingredients");
            for (int ingredient = 0; ingredient < ingredients.length(); ingredient++) {
                checkText(ingredients.get(ingredient), "ingredient", 120, false);
            }
            images.copy(optionalText(dish, "image", 96), true);
            if (!dish.has("recommendations")) {
                dish.put("recommendations", new JSONArray());
            }
            validateRecommendations(dish.getJSONArray("recommendations"), place, dishId, images);
        }
    }

    private static void validateRecommendations(JSONArray recommendations, String place, String dishId,
            Images images) throws IOException, JSONException {
        require(recommendations.length() <= 10, "Too many recommendations for one dish");
        Set<String> urls = new HashSet<>();
        for (int index = 0; index < recommendations.length(); index++) {
            JSONObject post = recommendations.getJSONObject(index);
            fields(post, "url", "platform", "author", "title", "thumbnail", "restaurantPlaceId",
                    "dishId", "verified", "type", "excerpt");
            require(Boolean.TRUE.equals(post.get("verified")), "Recommendation must have verified=true");
            require(!place.isEmpty() && place.equals(fieldText(post, "restaurantPlaceId", 256, false)),
                    "Recommendation must match the venue");
            require(dishId.equals(fieldText(post, "dishId", 80, false)), "Recommendation must match the dish");
            String url = fieldText(post, "url", 1024, false);
            String platform = fieldText(post, "platform", 24, false);
            require(RecommendationLinks.nativePackage(platform, url) != null,
                    "Recommendation must use a supported HTTPS original post");
            require(urls.add(url), "Duplicate recommendation URL");
            fieldText(post, "author", 100, false);
            fieldText(post, "title", 200, false);
            optionalText(post, "type", 40);
            optionalText(post, "excerpt", 500);
            images.copy(optionalText(post, "thumbnail", 96), false);
        }
    }

    private static void validateNutrition(JSONObject dish, String field, int maximum) throws JSONException {
        if (dish.isNull(field)) {
            dish.put(field, JSONObject.NULL);
        } else {
            validateNumber(dish.get(field), field, maximum);
        }
    }

    private static void validateNumber(Object value, String field, int maximum) {
        require(value instanceof BigDecimal, field + " must be a number");
        BigDecimal number = (BigDecimal) value;
        require(number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(maximum)) <= 0,
                field + " exceeds the allowed range");
    }

    private static void fields(JSONObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        object.keys().forEachRemaining(key -> require(names.contains(key), "Unexpected object field"));
    }

    private static String fieldText(JSONObject object, String field, int limit, boolean empty)
            throws JSONException {
        return checkText(object.get(field), field, limit, empty);
    }

    private static String optionalText(JSONObject object, String field, int limit) throws JSONException {
        if (!object.has(field)) {
            object.put(field, "");
        }
        return fieldText(object, field, limit, true);
    }

    private static String checkText(Object value, String field, int limit, boolean empty) {
        require(value instanceof String, field + " must be a string");
        String text = (String) value;
        require(text.length() <= limit && (empty || hasVisibleText(text)), "Invalid " + field + " length");
        require(text.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == 0xfffe || codePoint == 0xffff
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE),
                field + " contains an invalid character");
        return text;
    }

    private static boolean hasVisibleText(String text) {
        return text.codePoints().anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint) && Character.getType(codePoint) != Character.FORMAT);
    }

    private static byte[] readBounded(Path path, int maximum) throws IOException {
        require(Files.isRegularFile(path), "Input must be a regular file");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(maximum + 1);
            require(data.length > 0 && data.length <= maximum, "Input file size exceeds the allowed range");
            return data;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class PostCandidates {
        private static final int MAX_FILE_BYTES = 32 * 1024;
        private static final int MAX_CANDIDATES = 8;
        private static final int MAX_PARALLEL = 4;
        private static final int BATCH_SECONDS = 6;
        private static final int PAGE_BYTES = 2 * 1024 * 1024;
        private static final int OEMBED_BYTES = 16 * 1024;
        private static final String YOUTUBE = "https://www.youtube.com";
        private static final Pattern PLAYER = Pattern.compile("\\bytInitialPlayerResponse\\s*=\\s*(?=\\{)");
        private static final Pattern EMBED = Pattern.compile("^<iframe\\b[^>]*?\\s+src=\"https://www\\.youtube\\.com/embed/"
                + "([A-Za-z0-9_-]{11})(?:\\?feature=oembed)?\"");
        private static final Pattern POSSESSIVE = Pattern.compile("^[\\p{L}\\p{N}]+['’ʼ]s\\s+", Pattern.CASE_INSENSITIVE);
        private static final Pattern STREET_NUMBER = Pattern.compile("\\b[0-9]+[a-z]?\\b");
        private static final Map<String, String> ADDRESS_WORDS = Map.ofEntries(
                Map.entry("e", "east"), Map.entry("w", "west"), Map.entry("n", "north"), Map.entry("s", "south"),
                Map.entry("st", "street"), Map.entry("ave", "avenue"), Map.entry("rd", "road"),
                Map.entry("blvd", "boulevard"), Map.entry("dr", "drive"), Map.entry("ln", "lane"));

        private record Branch(String name, String street, String locality) {
            boolean matches(String evidence) {
                String text = words(evidence);
                return containsPhrase(text, name) && containsPhrase(addressWords(evidence), street)
                        && (locality.isEmpty() || containsPhrase(text, locality));
            }
        }

        private record Candidate(JSONObject dish, String url, String videoId, String indexedTitle, String dishName) {}

        private record Video(String id, String title, String author, String description) {}

        private record Verified(Candidate candidate, Video video) {}

        static void enrich(Path sidecar, JSONObject venue, JSONArray dishes) throws JSONException {
            String place = venue.getString("placeId");
            String[] address = venue.getString("address").split(",", 3);
            Branch branch = new Branch(words(venue.getString("name")), addressWords(address[0]),
                    address.length > 1 ? words(address[1]) : "");
            if (place.isEmpty() || branch.name().isEmpty() || !STREET_NUMBER.matcher(branch.street()).find()
                    || !branch.street().matches(".*\\p{L}.*") || !Files.isRegularFile(sidecar)) return;

            List<Candidate> candidates;
            try {
                candidates = read(sidecar, dishes, branch);
            } catch (IOException | JSONException | IllegalArgumentException | IllegalStateException failure) {
                System.err.println("Menu post candidates: invalid optional input");
                return;
            }
            if (candidates.isEmpty()) return;

            ExecutorService workers = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, candidates.size()), task -> {
                Thread thread = new Thread(task, "menu-post-verification");
                thread.setDaemon(true);
                return thread;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BATCH_SECONDS);
            List<Callable<Verified>> checks = new ArrayList<>();
            for (Candidate candidate : candidates) {
                checks.add(() -> verify(candidate, branch, deadline));
            }
            int added = 0;
            try {
                for (Future<Verified> check : workers.invokeAll(checks, BATCH_SECONDS, TimeUnit.SECONDS)) {
                    if (check.isCancelled()) continue;
                    try {
                        Verified result = check.get();
                        if (result != null && add(result, place)) added++;
                    } catch (ExecutionException ignored) {
                        // Verification is optional and a failed request cannot invalidate the menu.
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                workers.shutdownNow();
            }
            System.err.println("Menu post candidates: added " + added + " of " + candidates.size());
        }

        private static List<Candidate> read(Path sidecar, JSONArray dishes, Branch branch)
                throws IOException, JSONException {
            require(!Files.isSymbolicLink(sidecar), "Post candidates cannot use a symbolic link");
            byte[] bytes = readBounded(sidecar, MAX_FILE_BYTES);
            String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, JSONObject> byId = new HashMap<>();
            for (int index = 0; index < dishes.length(); index++) {
                JSONObject dish = dishes.getJSONObject(index);
                byId.put(dish.getString("id"), dish);
            }
            List<Candidate> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            try (JsonReader parser = new JsonReader(new StringReader(document))) {
                JSONArray entries = readArray(parser, 0);
                require(parser.peek() == JsonToken.END_DOCUMENT, "Unexpected content after post candidates");
                require(entries.length() <= MAX_CANDIDATES, "Too many post candidates");
                for (int index = 0; index < entries.length(); index++) {
                    try {
                        JSONObject entry = entries.getJSONObject(index);
                        fields(entry, "dishId", "platform", "url", "indexed");
                        String dishId = fieldText(entry, "dishId", 80, false);
                        JSONObject dish = byId.get(dishId);
                        if (dish == null || !"youtube".equalsIgnoreCase(fieldText(entry, "platform", 24, false))) continue;
                        String url = fieldText(entry, "url", 1024, false);
                        String videoId = RecommendationLinks.youtubeId(url);
                        JSONObject indexed = entry.getJSONObject("indexed");
                        fields(indexed, "url", "title", "excerpt");
                        String indexedUrl = fieldText(indexed, "url", 1024, false);
                        if (videoId == null || !videoId.equals(RecommendationLinks.youtubeId(indexedUrl))) continue;
                        String title = fieldText(indexed, "title", 200, false);
                        String excerpt = fieldText(indexed, "excerpt", 500, false);
                        String dishName = dishPhrase(dish.getString("name"), branch.name());
                        String evidence = title + " " + excerpt;
                        if (containsPhrase(words(title), dishName) && branch.matches(evidence)
                                && !hasVideo(dish.getJSONArray("recommendations"), videoId)
                                && seen.add(dishId + ":" + videoId)) {
                            candidates.add(new Candidate(dish, url, videoId, title, dishName));
                        }
                    } catch (JSONException | IllegalArgumentException ignored) {
                        // A bad candidate does not discard other entries.
                    }
                }
            }
            return candidates;
        }

        private static Verified verify(Candidate candidate, Branch branch, long deadline)
                throws JSONException {
            String watch = YOUTUBE + "/watch?v=" + candidate.videoId();
            Video original = null;
            try {
                long pageDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                original = pageVideo(fetch(watch, PAGE_BYTES, pageDeadline));
            } catch (IOException | JSONException ignored) {
                // The platform's oEmbed response can identify a post when the page is inaccessible.
            }
            if (original != null) {
                if (!branch.matches(original.title() + " " + original.description())) return null;
            } else {
                try {
                    original = embedVideo(fetch(YOUTUBE + "/oembed?format=json&url="
                            + URLEncoder.encode(watch, "UTF-8"), OEMBED_BYTES, deadline));
                } catch (IOException | JSONException | IllegalArgumentException failure) {
                    return null;
                }
                // oEmbed contains no description, so require the venue name in the live title as well.
                if (!containsPhrase(words(original.title()), branch.name())) return null;
            }
            if (!candidate.videoId().equals(original.id())
                    || !words(candidate.indexedTitle()).equals(words(original.title()))
                    || !containsPhrase(words(original.title()), candidate.dishName())) return null;
            checkText(original.title(), "title", 200, false);
            checkText(original.author(), "author", 100, false);
            return new Verified(candidate, original);
        }

        private static Video pageVideo(String html) throws JSONException {
            Matcher player = PLAYER.matcher(html);
            if (!player.find()) return null;
            JSONObject details = new JSONObject(html.substring(player.end())).optJSONObject("videoDetails");
            if (details == null || details.optBoolean("isPrivate")) return null;
            String description = details.getString("shortDescription");
            if (description.isBlank()) return null;
            return new Video(details.getString("videoId"), details.getString("title"), details.getString("author"),
                    description);
        }

        private static Video embedVideo(String json) throws JSONException {
            JSONObject embed = new JSONObject(json);
            Matcher frame = EMBED.matcher(embed.getString("html"));
            require("YouTube".equals(embed.getString("provider_name"))
                    && (YOUTUBE + "/").equals(embed.getString("provider_url"))
                    && "video".equals(embed.getString("type")) && frame.find(), "Invalid YouTube oEmbed response");
            return new Video(frame.group(1), embed.getString("title"), embed.getString("author_name"), "");
        }

        private static String fetch(String address, int maximum, long deadline) throws IOException {
            var url = URI.create(address).toURL();
            Proxy proxy = configuredProxy();
            HttpURLConnection connection = (HttpURLConnection) (proxy == null
                    ? url.openConnection() : url.openConnection(proxy));
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(remainingMillis(deadline));
                connection.setReadTimeout(remainingMillis(deadline));
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                        || connection.getContentLengthLong() > maximum) throw new IOException("Post metadata unavailable");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        remainingMillis(deadline);
                        int count = input.read(buffer);
                        if (count < 0) return response.toString(StandardCharsets.UTF_8.name());
                        if (response.size() + count > maximum) throw new IOException("Post metadata exceeds size limit");
                        response.write(buffer, 0, count);
                    }
                }
            } finally {
                connection.disconnect();
            }
        }

        private static int remainingMillis(long deadline) throws SocketTimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException();
            return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
        }

        private static Proxy configuredProxy() {
            for (String key : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
                String value = System.getenv(key);
                if (value == null || value.isBlank()) continue;
                try {
                    URI uri = URI.create(value);
                    if ("http".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                            && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)) {
                        int port = uri.getPort() == -1 ? 80 : uri.getPort();
                        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Continue with the next configured proxy or the system route.
                }
            }
            return null;
        }

        private static boolean add(Verified result, String place) throws JSONException {
            Candidate candidate = result.candidate();
            JSONArray recommendations = candidate.dish().getJSONArray("recommendations");
            if (recommendations.length() >= 10 || hasVideo(recommendations, candidate.videoId())) return false;
            Video video = result.video();
            recommendations.put(new JSONObject().put("url", candidate.url()).put("platform", "YouTube")
                    .put("author", video.author()).put("title", video.title()).put("thumbnail", "")
                    .put("restaurantPlaceId", place).put("dishId", candidate.dish().getString("id"))
                    .put("type", "video").put("verified", true));
            return true;
        }

        private static boolean hasVideo(JSONArray recommendations, String id) throws JSONException {
            for (int index = 0; index < recommendations.length(); index++) {
                JSONObject post = recommendations.getJSONObject(index);
                if (id.equals(RecommendationLinks.youtubeId(post.getString("url")))) return true;
            }
            return false;
        }

        private static String dishPhrase(String dish, String venueName) {
            String name = words(dish);
            if (name.startsWith(venueName + " ")) return name.substring(venueName.length() + 1);
            Matcher possessive = POSSESSIVE.matcher(dish);
            if (possessive.find() && venueName.startsWith(words(possessive.group()) + " ")) {
                return words(dish.substring(possessive.end()));
            }
            return name;
        }

        private static boolean containsPhrase(String words, String phrase) {
            return !phrase.isEmpty() && (" " + words + " ").contains(" " + phrase + " ");
        }

        private static String words(String text) {
            return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                    .replaceAll("['’ʼ]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        }

        private static String addressWords(String text) {
            String[] tokens = words(text).split(" ");
            for (int index = 0; index < tokens.length; index++) {
                tokens[index] = ADDRESS_WORDS.getOrDefault(tokens[index], tokens[index]);
            }
            return String.join(" ", tokens);
        }
    }

    private static final class Images {
        private final Path source;
        private final Path project;
        private final Set<String> copied = new HashSet<>();
        private int totalBytes;
        private long totalEncodedBytes;
        private long totalPixels;
        private boolean hasCover;

        Images(Path source, Path project) {
            this.source = source;
            this.project = project;
        }

        void copy(String reference, boolean useAsCover) throws IOException {
            if (reference.isEmpty()) return;
            require(reference.matches(IMAGE_PATH), "Image must be an images/ JPG, PNG or WebP filename");
            boolean isNew = copied.add(reference);
            boolean needsCover = useAsCover && !hasCover;
            if (!isNew && !needsCover) return;

            Path destination = project.resolve("assets").resolve(reference);
            Path file = isNew ? source.resolve(reference) : destination;
            require(!Files.isSymbolicLink(file) && !Files.isSymbolicLink(file.getParent()),
                    "Image paths cannot use symbolic links");
            byte[] data = readBounded(file, isNew ? MAX_IMAGE_BYTES : MAX_TOTAL_IMAGE_BYTES);
            if (isNew) {
                totalBytes += data.length;
                require(totalBytes <= MAX_TOTAL_IMAGE_BYTES, "Total image size exceeds the allowed range");
            }
            Bitmap bitmap = decodeImage(reference, data);
            try {
                if (isNew) {
                    writeBitmap(bitmap, destination, formatFor(reference), 90);
                    totalEncodedBytes += Files.size(destination);
                    require(totalEncodedBytes <= MAX_TOTAL_IMAGE_BYTES, "Packaged images exceed the size limit");
                }
                if (needsCover) writeCover(bitmap);
            } finally {
                bitmap.recycle();
            }
        }

        boolean saveArtwork(JSONObject dish, byte[] artwork) throws IOException, JSONException {
            long pixels = (long) MenuDishArtwork.SIZE * MenuDishArtwork.SIZE;
            if (totalEncodedBytes + artwork.length > MAX_TOTAL_IMAGE_BYTES
                    || totalPixels + pixels > MAX_TOTAL_IMAGE_PIXELS) {
                return false;
            }
            Path directory = project.resolve("assets/images");
            Files.createDirectories(directory);
            Path destination = Files.createTempFile(directory, "dish-", ".png");
            Files.write(destination, artwork);
            dish.put("image", "images/" + destination.getFileName());
            totalEncodedBytes += artwork.length;
            totalPixels += pixels;
            return true;
        }

        void writeGeneratedCover(JSONArray dishes) throws IOException, JSONException {
            if (hasCover) return;
            for (int index = 0; index < dishes.length(); index++) {
                String reference = dishes.getJSONObject(index).getString("image");
                if (reference.isEmpty()) continue;
                Bitmap bitmap = BitmapFactory.decodeFile(project.resolve("assets").resolve(reference).toString());
                require(bitmap != null, "Generated cover could not be decoded");
                try {
                    writeCover(bitmap);
                } finally {
                    bitmap.recycle();
                }
                return;
            }
        }

        private Bitmap decodeImage(String reference, byte[] data) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            String mime = reference.endsWith(".png") ? "image/png"
                    : reference.endsWith(".webp") ? "image/webp" : "image/jpeg";
            require(mime.equals(bounds.outMimeType) && bounds.outWidth > 0 && bounds.outHeight > 0
                    && bounds.outWidth <= 4096 && bounds.outHeight <= 4096
                    && (long) bounds.outWidth * bounds.outHeight <= 12_000_000,
                    "Image format or dimensions exceed the allowed range");
            totalPixels += (long) bounds.outWidth * bounds.outHeight;
            require(totalPixels <= MAX_TOTAL_IMAGE_PIXELS, "Total image dimensions exceed the allowed range");
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = Math.max(bounds.outWidth, bounds.outHeight) > 1600 ? 2 : 1;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decode);
            require(bitmap != null, "Image could not be decoded");
            return bitmap;
        }

        private void writeCover(Bitmap bitmap) throws IOException {
            float scale = Math.min(1f, 480f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Bitmap cover = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            try {
                writeBitmap(cover, project.resolve("res/drawable-nodpi/taku_menu_app_cover.png"),
                        Bitmap.CompressFormat.PNG, 100);
                hasCover = true;
            } finally {
                if (cover != bitmap) cover.recycle();
            }
        }

        private static Bitmap.CompressFormat formatFor(String reference) {
            if (reference.endsWith(".png")) return Bitmap.CompressFormat.PNG;
            if (reference.endsWith(".webp")) return Bitmap.CompressFormat.WEBP_LOSSY;
            return Bitmap.CompressFormat.JPEG;
        }

        private static void writeBitmap(Bitmap bitmap, Path file, Bitmap.CompressFormat format, int quality)
                throws IOException {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                require(bitmap.compress(format, quality, output), "Image could not be encoded");
            }
        }
    }

    private MenuData() {}
}
