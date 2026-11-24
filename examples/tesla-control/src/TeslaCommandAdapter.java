package org.agentos.research;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import dalvik.system.PathClassLoader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TeslaCommandAdapter {
    private static final String TESLA_PACKAGE = "com.teslamotors.tesla";
    private static final String RECEIVER_CLASS = "com.tesla.command.CommandRequestBroadcastReceiver";
    private static final String REQUEST_EXTRA = "NotificationAction:CommandRequest";

    public static void main(String[] args) {
        try {
            execute(args);
            System.exit(0);
        } catch (CheckFailure failure) {
            System.err.println("ADAPTER_FAIL " + failure.code);
            System.exit(2);
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            System.err.println("ADAPTER_FAIL type=" + error.getClass().getSimpleName()
                    + " causeType=" + (cause == null ? "none" : cause.getClass().getSimpleName()));
            System.exit(2);
        }
    }

    private static void execute(String[] args) throws Exception {
        require(args.length >= 5, "EXPECTED_MODE_CATALOG_VIN_ACTION_UUID_PARAMETERS");
        String mode = args[0];
        require(mode.equals("check") || mode.equals("send") || mode.equals("test"), "UNSUPPORTED_MODE");
        String vin = args[2];
        require(vin.matches("[A-HJ-NPR-Z0-9]{17}"), "INVALID_VIN");
        String actionId = args[3];
        require(actionId.matches("[a-z][a-z0-9_]{0,63}"), "INVALID_ACTION_ID");
        require(args[4].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
                + "-[0-9a-f]{4}-[0-9a-f]{12}"), "INVALID_UUID");
        String requestId = UUID.fromString(args[4]).toString().replace("-", "");
        JSONObject catalog = new JSONObject(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        require("taku.tesla-capabilities/1".equals(catalog.getString("schema")), "CATALOG_SCHEMA");
        Application application = new Application(catalog.getJSONObject("application"));
        JSONObject action = catalog.getJSONObject("actions").getJSONObject(actionId);
        require("command".equals(action.getString("route")), "WIDGET_ROUTE_NOT_HANDLED");
        String status = action.getString("status");
        require(status.equals("vehicle_verified") || status.equals("prepared"), "ACTION_STATUS");
        require(!mode.equals("send") || status.equals("vehicle_verified"), "ACTION_REQUIRES_VEHICLE_TEST");
        Parameters parameters = parameters(action.getJSONArray("parameters"), args);
        String sourceContext = action.getString("sourceContext");
        require(!sourceContext.isEmpty(), "SOURCE_CONTEXT_EMPTY");
        String transport = action.optString("transport", "TRANSPORT_BLUETOOTH");
        require(transport.equals("TRANSPORT_BLUETOOTH"),
                "ACTION_TRANSPORT");
        require(application.apkSha256.equals(sha256(Paths.get(application.apkPath))), "APK_HASH_MISMATCH");
        Types types = new Types(new PathClassLoader(application.apkPath,
                TeslaCommandAdapter.class.getClassLoader()), catalog);
        Node command = recipe(action.get("value"), parameters, types);
        JSONArray wrappers = action.getJSONArray("wrappers");
        require(wrappers.length() > 0, "COMMAND_WRAPPER_REQUIRED");
        for (int index = wrappers.length() - 1; index >= 0; index--) {
            command = wrapper(wrappers.getJSONObject(index), command, parameters, types);
        }
        require(command.value != null && command.value.getClass() == types.commandAction,
                "COMMAND_ACTION_TYPE_MISMATCH");
        Node request = request(command, vin, requestId, sourceContext, transport, types);
        verify(request.value, request, types);
        byte[] encoded = (byte[]) types.message.getMethod("encode").invoke(request.value);
        require(encoded.length > 0, "ENCODED_BYTES_EMPTY");
        Object adapter = types.message.getMethod("adapter").invoke(request.value);
        require(types.protoAdapter.isInstance(adapter), "PROTO_ADAPTER_TYPE");
        Object decoded = types.protoAdapter.getMethod("decode", byte[].class)
                .invoke(adapter, (Object) encoded);
        verify(decoded, request, types);
        PreparedTarget target = prepareTarget(application);
        boolean submitted = !mode.equals("check");
        if (submitted) {
            Intent intent = new Intent().setComponent(target.receiver).putExtra(REQUEST_EXTRA, encoded);
            target.context.sendBroadcast(intent);
            System.out.println("BROADCAST_SUBMITTED");
        }
        JSONObject summary = new JSONObject();
        summary.put("action", actionId);
        summary.put("mode", mode);
        summary.put("status", status);
        summary.put("transport", transport);
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Object> entry : parameters.values.entrySet()) {
            Object value = entry.getValue();
            values.put(entry.getKey(), value instanceof String
                    ? ((String) value).replace(vin, "<VIN_REDACTED>") : value);
        }
        summary.put("params", values);
        summary.put("roundtripPassed", true);
        summary.put("nonSelectedActions", 0);
        summary.put("encodedBytes", encoded.length);
        summary.put("encodedSha256", sha256(encoded));
        summary.put("systemContextReady", true);
        summary.put("receiverValidated", true);
        summary.put("broadcastSubmitted", submitted);
        System.out.println(summary);
    }

    private static Parameters parameters(JSONArray definitions, String[] args) throws Exception {
        require(args.length == definitions.length() + 5, "PARAMETER_COUNT");
        Parameters result = new Parameters();
        for (int index = 0; index < definitions.length(); index++) {
            JSONObject definition = definitions.getJSONObject(index);
            String name = definition.getString("name");
            require(name.matches("[a-z][a-z0-9_]{0,63}") && !result.values.containsKey(name),
                    "PARAMETER_NAME");
            String type = definition.getString("type");
            String raw = args[index + 5];
            Object value;
            if (type.equals("number") || type.equals("integer")) {
                BigDecimal number = new BigDecimal(raw);
                require(!type.equals("integer") || number.stripTrailingZeros().scale() <= 0,
                        "INTEGER_REQUIRED");
                BigDecimal minimum = definition.has("minimum")
                        ? decimal(definition.get("minimum")) : BigDecimal.ZERO;
                if (definition.has("minimum")) {
                    require(number.compareTo(minimum) >= 0, "PARAMETER_BELOW_MINIMUM");
                }
                if (definition.has("maximum")) {
                    require(number.compareTo(decimal(definition.get("maximum"))) <= 0,
                            "PARAMETER_ABOVE_MAXIMUM");
                }
                if (definition.has("step")) {
                    BigDecimal step = decimal(definition.get("step"));
                    require(step.signum() > 0, "CATALOG_STEP");
                    require(number.subtract(minimum).remainder(step).signum() == 0, "PARAMETER_STEP");
                }
                value = number;
            } else if (type.equals("boolean")) {
                require(raw.equals("true") || raw.equals("false"), "BOOLEAN_REQUIRED");
                value = Boolean.valueOf(raw);
            } else if (type.equals("enum")) {
                JSONArray allowed = definition.getJSONArray("values");
                value = null;
                for (int item = 0; item < allowed.length(); item++) {
                    Object candidate = allowed.get(item);
                    if (candidate instanceof String && candidate.equals(raw)) {
                        value = candidate;
                    }
                }
                require(value != null, "PARAMETER_ENUM");
            } else {
                require(type.equals("string"), "PARAMETER_TYPE");
                require(!raw.isBlank() && raw.equals(raw.strip()), "TEXT_EMPTY_OR_PADDED");
                require(raw.codePoints().noneMatch(Character::isISOControl), "TEXT_CONTROL_CHARACTER");
                int length = raw.codePointCount(0, raw.length());
                if (definition.has("minLength")) {
                    require(length >= decimal(definition.get("minLength")).intValueExact(),
                            "TEXT_TOO_SHORT");
                }
                if (definition.has("maxLength")) {
                    require(length <= decimal(definition.get("maxLength")).intValueExact(),
                            "TEXT_TOO_LONG");
                }
                if (definition.has("format")) {
                    require(definition.getString("format").equals("address"), "TEXT_FORMAT");
                    require(!java.util.regex.Pattern.compile("(?i)(https?://|geo:|intent:|maps:)")
                            .matcher(raw).find(), "ADDRESS_TEXT_REQUIRED");
                }
                value = raw;
            }
            if (!(value instanceof Number)) {
                require(!definition.has("minimum") && !definition.has("maximum")
                        && !definition.has("step"), "NON_NUMERIC_RANGE");
            }
            if (definition.has("values")) {
                boolean found = false;
                JSONArray allowed = definition.getJSONArray("values");
                for (int item = 0; item < allowed.length(); item++) {
                    Object candidate = allowed.get(item);
                    found |= value instanceof Number && candidate instanceof Number
                            ? decimal(value).compareTo(decimal(candidate)) == 0 : value.equals(candidate);
                }
                require(found, "PARAMETER_VALUES");
            }
            result.values.put(name, value);
            result.strings.put(name, raw);
        }
        return result;
    }

    private static Node recipe(Object definition, Parameters parameters, Types types) throws Exception {
        if (definition == JSONObject.NULL) {
            return Node.scalar(null);
        }
        if (definition instanceof JSONArray) {
            JSONArray array = (JSONArray) definition;
            List<Node> elements = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                require(item.has("class"), "LIST_ELEMENT_CLASS_REQUIRED");
                elements.add(recipe(item, parameters, types));
            }
            return Node.list(elements);
        }
        if (!(definition instanceof JSONObject)) {
            require(definition instanceof String || definition instanceof Number
                    || definition instanceof Boolean, "RECIPE_VALUE");
            return Node.scalar(definition);
        }
        JSONObject object = (JSONObject) definition;
        if (object.has("whenParam")) {
            require(object.length() == 3 && object.has("equals") && object.has("value"),
                    "CONDITIONAL_RECIPE");
            String parameter = object.getString("whenParam");
            require(parameters.strings.containsKey(parameter), "UNKNOWN_CONDITIONAL_PARAMETER");
            return parameters.strings.get(parameter).equals(object.getString("equals"))
                    ? recipe(object.get("value"), parameters, types) : Node.scalar(null);
        }
        if (object.has("param")) {
            require(object.length() == 1, "PARAMETER_RECIPE");
            String parameter = object.getString("param");
            require(parameters.values.containsKey(parameter), "UNKNOWN_RECIPE_PARAMETER");
            return Node.scalar(parameters.values.get(parameter));
        }
        Class<?> owner = types.dataType(object.getString("class"));
        if (object.has("enum")) {
            require(object.length() == 2, "ENUM_RECIPE");
            return Node.scalar(enumValue(owner, object.getString("enum")));
        }
        require(object.length() == 2 && object.has("fields"), "MESSAGE_RECIPE");
        LinkedHashMap<String, Node> fields = new LinkedHashMap<>();
        JSONArray ordered = object.getJSONArray("fields");
        for (int index = 0; index < ordered.length(); index++) {
            JSONArray pair = ordered.getJSONArray(index);
            require(pair.length() == 2, "FIELD_RECIPE_PAIR");
            String name = pair.getString(0);
            require(!fields.containsKey(name), "DUPLICATE_RECIPE_FIELD");
            fields.put(name, recipe(pair.get(1), parameters, types));
        }
        return message(owner, fields, types);
    }

    private static Node message(Class<?> owner, LinkedHashMap<String, Node> fields, Types types)
            throws Exception {
        require(types.message.isAssignableFrom(owner), "GENERATED_MESSAGE_REQUIRED");
        require(instanceFields(owner).equals(fields.keySet()), "MESSAGE_FIELD_COVERAGE");
        Class<?>[] signature = new Class<?>[fields.size() + 1];
        Object[] arguments = new Object[signature.length];
        LinkedHashMap<String, Node> expected = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Node> entry : fields.entrySet()) {
            Field field = owner.getDeclaredField(entry.getKey());
            require(!Modifier.isStatic(field.getModifiers()), "INSTANCE_FIELD_REQUIRED");
            signature[index] = field.getType();
            Node value = coerce(entry.getValue(), signature[index], types);
            expected.put(entry.getKey(), value);
            arguments[index++] = value.value;
        }
        signature[index] = types.byteString;
        arguments[index] = types.empty;
        Constructor<?> constructor = owner.getConstructor(signature);
        require(!constructor.isSynthetic(), "PRIMARY_MESSAGE_CTOR_REQUIRED");
        return Node.message(constructor.newInstance(arguments), expected);
    }

    private static Node wrapper(JSONObject definition, Node action, Parameters parameters, Types types)
            throws Exception {
        Class<?> owner = types.dataType(definition.getString("class"));
        require(types.message.isAssignableFrom(owner) && action.value != null, "WRAPPER_TYPE");
        String name = definition.getString("field");
        Field field = owner.getDeclaredField(name);
        require(!Modifier.isStatic(field.getModifiers()) && field.getType().isInstance(action.value),
                "WRAPPER_FIELD_TYPE");
        int count = decimal(definition.get("count")).intValueExact();
        int index = decimal(definition.get("index")).intValueExact();
        require(count > 1 && index >= 0 && index < count - 1, "WRAPPER_SLOT");
        Constructor<?> selected = null;
        for (Constructor<?> constructor : owner.getConstructors()) {
            Class<?>[] signature = constructor.getParameterTypes();
            if (signature.length == count && signature[count - 1] == types.byteString) {
                require(selected == null && !constructor.isSynthetic(), "WRAPPER_CTOR_AMBIGUOUS");
                selected = constructor;
            }
        }
        require(selected != null, "WRAPPER_CTOR_MISSING");
        Class<?>[] signature = selected.getParameterTypes();
        require(signature[index] == field.getType(), "WRAPPER_INDEX_TYPE_MISMATCH");
        Object[] arguments = new Object[count];
        arguments[index] = action.value;
        arguments[count - 1] = types.empty;
        Set<Integer> assignedSlots = new LinkedHashSet<>();
        Set<String> assignedFields = new LinkedHashSet<>();
        assignedSlots.add(index);
        assignedFields.add(name);
        LinkedHashMap<String, Node> fields = new LinkedHashMap<>();
        for (String fieldName : instanceFields(owner)) {
            fields.put(fieldName, fieldName.equals(name) ? action : Node.scalar(null));
        }
        JSONArray defaults = definition.has("defaults") ? definition.getJSONArray("defaults") : new JSONArray();
        for (int item = 0; item < defaults.length(); item++) {
            JSONObject binding = defaults.getJSONObject(item);
            String defaultName = binding.getString("field");
            int slot = decimal(binding.get("index")).intValueExact();
            require(slot >= 0 && slot < count - 1 && assignedSlots.add(slot)
                    && assignedFields.add(defaultName), "WRAPPER_DEFAULT_COLLISION");
            Field defaultField = owner.getDeclaredField(defaultName);
            require(!Modifier.isStatic(defaultField.getModifiers())
                    && signature[slot] == defaultField.getType(), "WRAPPER_DEFAULT_TYPE");
            Node value = coerce(recipe(binding.get("value"), parameters, types), signature[slot], types);
            require(!types.message.isAssignableFrom(defaultField.getType()) || value.value == null,
                    "WRAPPER_EXTRA_MESSAGE");
            arguments[slot] = value.value;
            fields.put(defaultName, value);
        }
        for (int slot = 0; slot < count - 1; slot++) {
            require(!signature[slot].isPrimitive() || assignedSlots.contains(slot),
                    "WRAPPER_PRIMITIVE_DEFAULT_REQUIRED");
        }
        return Node.message(selected.newInstance(arguments), fields);
    }

    private static Node request(Node action, String vin, String id, String sourceContext,
                                String transport, Types types)
            throws Exception {
        LinkedHashMap<String, Node> fields = new LinkedHashMap<>();
        fields.put("id", Node.scalar(id));
        fields.put("vehicleId", Node.scalar(""));
        fields.put("transports", Node.list(Collections.singletonList(
                Node.scalar(enumValue(types.transport, transport)))));
        fields.put("source", Node.scalar(enumValue(types.source, "SOURCE_WIDGET")));
        fields.put("oapiHeaders", Node.scalar(Collections.emptyMap()));
        fields.put("letSleep", Node.scalar(false));
        fields.put("bypassCounter", Node.scalar(false));
        fields.put("action", action);
        fields.put("vin", Node.scalar(vin));
        fields.put("receivedRequestBytesTimestamp", Node.scalar(0L));
        fields.put("useIncorrectHandleInitially", Node.scalar(false));
        fields.put("useIncorrectCounterInitially", Node.scalar(false));
        fields.put("useIncorrectEpochInitially", Node.scalar(false));
        fields.put("useIncorrectExpiryTimeTooLongInitially", Node.scalar(false));
        fields.put("useIncorrectTimeExpiredInitially", Node.scalar(false));
        fields.put("useAnotherKeysHandleInitially", Node.scalar(false));
        fields.put("jsStartTimestamp", Node.scalar(0L));
        fields.put("jsBridgeStartTimestamp", Node.scalar(0L));
        fields.put("nativeStartTimestamp", Node.scalar(System.currentTimeMillis()));
        fields.put("bridgeJsToNativeDurationMilliseconds", Node.scalar(0));
        fields.put("sourceContext", Node.scalar(sourceContext));
        require(types.requestFields.length() == fields.size(), "REQUEST_ALIAS_COUNT");
        LinkedHashMap<String, Node> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : fields.entrySet()) {
            String actualName = types.requestFields.getString(entry.getKey());
            require(!mapped.containsKey(actualName), "REQUEST_ALIAS_DUPLICATE");
            mapped.put(actualName, entry.getValue());
        }
        return message(types.request, mapped, types);
    }

    private static Node coerce(Node node, Class<?> target, Types types) throws Exception {
        Object value = node.value;
        if (value == null) {
            require(!target.isPrimitive(), "NULL_PRIMITIVE_FIELD");
            return node;
        }
        if (target.isEnum()) {
            Object converted = value instanceof String ? enumValue(target, (String) value) : value;
            require(target.isInstance(converted), "ENUM_FIELD_TYPE_MISMATCH");
            return Node.scalar(converted);
        }
        if (value instanceof Number) {
            BigDecimal number = decimal(value);
            if (target == int.class || target == Integer.class) return Node.scalar(number.intValueExact());
            if (target == long.class || target == Long.class) return Node.scalar(number.longValueExact());
            if (target == float.class || target == Float.class) {
                float converted = number.floatValue();
                require(Float.isFinite(converted), "FLOAT_NOT_FINITE");
                return Node.scalar(converted);
            }
            if (target == double.class || target == Double.class) {
                double converted = number.doubleValue();
                require(Double.isFinite(converted), "DOUBLE_NOT_FINITE");
                return Node.scalar(converted);
            }
        }
        require((target == boolean.class && value instanceof Boolean) || target.isInstance(value),
                "RECIPE_FIELD_TYPE_MISMATCH");
        return node;
    }

    private static Object enumValue(Class<?> owner, String name) throws Exception {
        require(owner.isEnum(), "ENUM_TYPE");
        for (Object constant : owner.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new CheckFailure("ENUM_CONSTANT");
    }

    private static Set<String> instanceFields(Class<?> owner) {
        Set<String> result = new LinkedHashSet<>();
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) result.add(field.getName());
        }
        return result;
    }

    private static void verify(Object actual, Node expected, Types types) throws Exception {
        if (expected.value == null) {
            require(actual == null, "UNEXPECTED_NON_NULL_FIELD");
        } else if (expected.fields != null) {
            require(actual != null && actual.getClass() == expected.value.getClass(), "MESSAGE_TYPE_MISMATCH");
            require(instanceFields(actual.getClass()).equals(expected.fields.keySet()), "DECODED_FIELD_COVERAGE");
            Object unknown = types.message.getMethod("unknownFields").invoke(actual);
            require(types.empty.equals(unknown), "UNKNOWN_FIELDS_NOT_EMPTY");
            for (Map.Entry<String, Node> entry : expected.fields.entrySet()) {
                Field field = actual.getClass().getDeclaredField(entry.getKey());
                require(!Modifier.isStatic(field.getModifiers()), "OWN_INSTANCE_FIELD_REQUIRED");
                field.setAccessible(true);
                verify(field.get(actual), entry.getValue(), types);
            }
        } else if (expected.elements != null) {
            require(actual instanceof List && ((List<?>) actual).size() == expected.elements.size(),
                    "LIST_MISMATCH");
            for (int index = 0; index < expected.elements.size(); index++) {
                verify(((List<?>) actual).get(index), expected.elements.get(index), types);
            }
        } else {
            require(expected.value.equals(actual), "FIELD_VALUE_MISMATCH");
        }
    }

    private static BigDecimal decimal(Object value) throws CheckFailure {
        require(value instanceof Number, "NUMERIC_VALUE_REQUIRED");
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
    }

    private static PreparedTarget prepareTarget(Application application) throws Exception {
        require(Process.myUid() == 0, "ROOT_UID_REQUIRED");
        require(Process.myUserHandle().equals(UserHandle.getUserHandleForUid(0)), "USER_0_REQUIRED");
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Object candidate = activityThread.getMethod("getSystemContext").invoke(thread);
        require(candidate instanceof Context, "SYSTEM_CONTEXT_UNAVAILABLE");
        Context context = (Context) candidate;
        require("android".equals(context.getPackageName()), "SYSTEM_CONTEXT_PACKAGE_MISMATCH");
        ComponentName receiver = new ComponentName(TESLA_PACKAGE, RECEIVER_CLASS);
        PackageManager packages = context.getPackageManager();
        ActivityInfo info = packages.getReceiverInfo(receiver, 0);
        require(TESLA_PACKAGE.equals(info.packageName) && RECEIVER_CLASS.equals(info.name),
                "RECEIVER_COMPONENT_MISMATCH");
        require(info.enabled && info.applicationInfo != null && info.applicationInfo.enabled,
                "RECEIVER_DISABLED");
        require(TESLA_PACKAGE.equals(info.applicationInfo.packageName), "RECEIVER_APPLICATION_MISMATCH");
        PackageInfo installed = packages.getPackageInfo(application.packageName, 0);
        require(application.versionName.equals(installed.versionName)
                && application.versionCode == installed.getLongVersionCode(), "TESLA_VERSION_MISMATCH");
        return new PreparedTarget(context, receiver);
    }

    private static void require(boolean condition, String code) throws CheckFailure {
        if (!condition) throw new CheckFailure(code);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static final class Parameters {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        final LinkedHashMap<String, String> strings = new LinkedHashMap<>();
    }

    private static final class Node {
        final Object value;
        final LinkedHashMap<String, Node> fields;
        final List<Node> elements;

        Node(Object value, LinkedHashMap<String, Node> fields, List<Node> elements) {
            this.value = value;
            this.fields = fields;
            this.elements = elements;
        }

        static Node scalar(Object value) { return new Node(value, null, null); }
        static Node message(Object value, LinkedHashMap<String, Node> fields) {
            return new Node(value, fields, null);
        }
        static Node list(List<Node> elements) {
            List<Object> values = new ArrayList<>();
            for (Node element : elements) values.add(element.value);
            return new Node(Collections.unmodifiableList(values), null, elements);
        }
    }

    private static final class PreparedTarget {
        final Context context;
        final ComponentName receiver;

        PreparedTarget(Context context, ComponentName receiver) {
            this.context = context;
            this.receiver = receiver;
        }
    }

    private static final class Application {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String apkPath;
        final String apkSha256;

        Application(JSONObject definition) throws Exception {
            packageName = definition.getString("package");
            versionName = definition.getString("versionName");
            versionCode = decimal(definition.get("versionCode")).longValueExact();
            apkPath = definition.getString("apkPath");
            apkSha256 = definition.getString("apkSha256");
            require(TESLA_PACKAGE.equals(packageName), "APPLICATION_PACKAGE");
            require(versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+")
                    && versionCode > 0, "APPLICATION_VERSION");
            require(apkPath.equals("/data/local/agentos/materials/tesla/" + versionName + "/base.apk"),
                    "APPLICATION_MATERIAL_PATH");
            require(apkSha256.matches("[a-f0-9]{64}"), "APPLICATION_MATERIAL_SHA256");
        }
    }

    private static final class Types {
        final ClassLoader loader;
        final Set<String> declaredClasses = new LinkedHashSet<>();
        final Class<?> commandAction;
        final Class<?> request;
        final Class<?> source;
        final Class<?> transport;
        final Class<?> byteString;
        final Class<?> message;
        final Class<?> protoAdapter;
        final JSONObject requestFields;
        final Object empty;

        Types(ClassLoader loader, JSONObject catalog) throws Exception {
            this.loader = loader;
            JSONObject codec = catalog.getJSONObject("codec");
            collectClasses(catalog.getJSONObject("actions"), declaredClasses);
            for (String key : new String[] {"request", "commandAction", "source", "transport"}) {
                declaredClasses.add(codec.getString(key));
            }
            byteString = Class.forName(codec.getString("byteString"), false, loader);
            message = Class.forName(codec.getString("message"), false, loader);
            protoAdapter = Class.forName(codec.getString("protoAdapter"), false, loader);
            require(message.getMethod("encode").getReturnType() == byte[].class
                    && message.getMethod("unknownFields").getReturnType() == byteString
                    && message.getMethod("adapter").getReturnType() == protoAdapter, "CODEC_METHOD_TYPES");
            protoAdapter.getMethod("decode", byte[].class);
            commandAction = dataType(codec.getString("commandAction"));
            request = dataType(codec.getString("request"));
            source = dataType(codec.getString("source"));
            transport = dataType(codec.getString("transport"));
            require(message.isAssignableFrom(commandAction) && message.isAssignableFrom(request)
                    && source.isEnum() && transport.isEnum(), "CODEC_CONTAINER_TYPES");
            requestFields = codec.getJSONObject("requestFields");
            empty = byteString.getConstructor(byte[].class).newInstance((Object) new byte[0]);
        }

        Class<?> dataType(String name) throws Exception {
            require(declaredClasses.contains(name), "UNDECLARED_DATA_CLASS");
            Class<?> type = Class.forName(name, false, loader);
            require(message.isAssignableFrom(type) || type.isEnum(), "DATA_CLASS_TYPE");
            return type;
        }
    }

    private static void collectClasses(Object value, Set<String> names) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("class")) names.add(object.getString("class"));
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectClasses(object.get(keys.next()), names);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) collectClasses(array.get(index), names);
        }
    }

    private static final class CheckFailure extends Exception {
        final String code;
        CheckFailure(String code) { this.code = code; }
    }
}
