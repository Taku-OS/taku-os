package org.agentos.generated.menu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MenuData {
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 36 * 1024 * 1024;
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
        validateMenu(menu, new Images(source.getParent(), project));
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
            require(totalPixels <= 90_000_000, "Total image dimensions exceed the allowed range");
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
