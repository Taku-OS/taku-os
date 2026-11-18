package org.taku.plugins.host;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.taku.plugins.TakuPlugin;

public final class PluginManager implements AutoCloseable {
    private static final long LIMIT = 128L * 1024 * 1024;
    private final Path root, installed, staging, runtime, registry;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String,PluginRecord> records = new TreeMap<>();
    private final Map<String,Running> running = new HashMap<>();
    private final PluginLoader pluginLoader;
    private final EvidenceLedger evidence;

    private static final class Running implements AutoCloseable {
        final ClassLoader loader;
        final PluginLoader factory;
        final TakuPlugin plugin;
        final RuntimeContext context;
        final Path snapshot;
        Running(ClassLoader loader, PluginLoader factory, TakuPlugin plugin, RuntimeContext context, Path snapshot) { this.loader = loader; this.factory = factory; this.plugin = plugin; this.context = context; this.snapshot = snapshot; }
        public void close() throws Exception { try { plugin.onStop(); } catch (AssertionError | LinkageError error) { throw new Exception(error); } finally { if (loader != null) factory.close(loader); deleteTree(snapshot); } }
    }

    public PluginManager(Path root) throws Exception {
        this(root, new JvmPluginLoader());
    }
    public PluginManager(Path root, PluginLoader pluginLoader) throws Exception {
        this.pluginLoader = Objects.requireNonNull(pluginLoader);
        this.root = root.toAbsolutePath().normalize();
        installed = this.root.resolve("installed"); staging = this.root.resolve("staging"); runtime = this.root.resolve("runtime"); registry = this.root.resolve("registry.json");
        Files.createDirectories(installed); Files.createDirectories(staging); Files.createDirectories(runtime);
        lockChannel = FileChannel.open(this.root.resolve("manager.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); } catch (OverlappingFileLockException error) { acquired = null; }
        if (acquired == null) { lockChannel.close(); throw new PluginException("LOCKED", "another manager owns this root"); }
        lock = acquired;
        try {
            evidence = new EvidenceLedger(this.root);
            readRegistry();
            cleanup();
            for (PluginRecord record : records.values()) if (record.enabled) {
                try { running.put(record.id, start(installed.resolve(record.generation))); record.error = null; }
                catch (Exception error) { record.error = error.toString(); }
            }
            persist();
        } catch (Exception error) { lock.release(); lockChannel.close(); throw error; }
    }

    synchronized Object evidence(String id) throws Exception { return id == null ? evidence.list() : evidence.read(id); }
    synchronized String evidenceTargetHash(String operation, Object argument) {
        if (!(argument instanceof String)) return null;
        try {
            if (operation.equals("install")) return EvidenceLedger.targetHash(Paths.get((String)argument));
            return EvidenceLedger.targetHash(installed.resolve(require((String)argument).generation));
        } catch (Exception error) { return null; }
    }
    @FunctionalInterface private interface Operation<T> { T execute() throws Exception; }
    private <T> T audited(String operation, String subject, Map<String,Object> params, Operation<T> action) throws Exception {
        Map<String,Object> record = new LinkedHashMap<>();
        record.put("subject", subject);
        String targetHash = evidenceTargetHash(operation, subject);
        record.put("targetHash", targetHash);
        record.put("targetHashAlgorithm", "sha256-file-list-v1");
        record.put("operation", operation);
        record.put("params", params);
        record.put("confidence", "observed"); record.put("authority", "controlled-run");
        record.put("limitations", List.of("Target was hashed before execution and may change", "External side effects are not independently verified"));
        record.put("coverage", "partial"); record.put("locations", List.of()); record.put("links", List.of());
        record.put("unknown", targetHash == null ? List.of("target hash", "external side effects") : List.of("external side effects"));
        record.put("externalContent", true); record.put("untrustedContentFields", List.of("subject", "params", "result"));
        T result;
        try {
            result = action.execute();
            record.put("result", result); record.put("summary", operation + " completed");
        } catch (Exception error) {
            record.put("result", Map.of("error", Map.of("code", error instanceof PluginException ? ((PluginException)error).code : "INTERNAL_ERROR", "message", String.valueOf(error.getMessage()))));
            record.put("summary", operation + " failed");
            try { evidence.append(record); } catch (Exception failure) { throw new PluginException("EVIDENCE_WRITE_FAILED", "operation failed and evidence could not be saved: " + failure.getMessage(), error); }
            throw error;
        }
        try { evidence.append(record); }
        catch (Exception error) { throw new PluginException("EVIDENCE_WRITE_FAILED", "operation completed but evidence could not be saved: " + error.getMessage(), error); }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void readRegistry() throws Exception {
        if (!Files.exists(registry)) return;
        Object parsed;
        try { parsed = Json.parse(new String(Files.readAllBytes(registry), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException error) { throw new PluginException("CORRUPT_REGISTRY", error.getMessage(), error); }
        if (!(parsed instanceof Map)) throw new PluginException("CORRUPT_REGISTRY", "registry must be object");
        Map<String,Object> value = (Map<String,Object>)parsed;
        if (!(value.get("version") instanceof Number) || ((Number)value.get("version")).intValue() != 1 || !(value.get("plugins") instanceof List)) throw new PluginException("CORRUPT_REGISTRY", "unsupported registry");
        for (Object item : (List<?>)value.get("plugins")) {
            if (!(item instanceof Map)) throw new PluginException("CORRUPT_REGISTRY", "invalid record");
            Map<String,Object> record = (Map<String,Object>)item;
            if (!(record.get("id") instanceof String) || !(record.get("generation") instanceof String) || !(record.get("enabled") instanceof Boolean)) throw new PluginException("CORRUPT_REGISTRY", "invalid record fields");
            String id = (String)record.get("id"), generation = (String)record.get("generation");
            if (!generation.matches("[0-9a-f-]{36}") || records.containsKey(id)) throw new PluginException("CORRUPT_REGISTRY", "invalid generation or duplicate id");
            Manifest manifest;
            try { manifest = Manifest.read(installed.resolve(generation)); }
            catch (PluginException error) { throw new PluginException("CORRUPT_REGISTRY", "installed manifest unavailable: " + id, error); }
            if (!manifest.id.equals(id)) throw new PluginException("CORRUPT_REGISTRY", "record and manifest differ");
            Object previous = record.get("previousGeneration");
            if (previous != null && (!(previous instanceof String) || !((String)previous).matches("[0-9a-f-]{36}"))) throw new PluginException("CORRUPT_REGISTRY", "invalid previous generation");
            records.put(id, new PluginRecord(id, generation, (Boolean)record.get("enabled"), (String)record.get("error"), (String)previous));
        }
    }

    private void persist() throws IOException {
        List<Object> list = new ArrayList<>();
        for (PluginRecord record : records.values()) list.add(record.json());
        byte[] bytes = Json.write(Map.of("version", 1, "plugins", list)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path temp = Files.createTempFile(root, ".registry-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
            Files.move(temp, registry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private void cleanup() throws IOException {
        Set<String> referenced = new HashSet<>();
        for (PluginRecord record : records.values()) {
            referenced.add(record.generation);
            if (record.previousGeneration != null) referenced.add(record.previousGeneration);
        }
        for (Path base : List.of(staging, runtime, installed)) try (DirectoryStream<Path> children = Files.newDirectoryStream(base)) {
            for (Path child : children) if (!base.equals(installed) || !referenced.contains(child.getFileName().toString())) deleteTree(child);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            for (Path item : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(item);
        }
    }
    private static void copyTree(Path source, Path dest) throws IOException {
        final long[] bytes = {0}; final int[] count = {0};
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>)paths::iterator) {
                if (++count[0] > 4096 || Files.isSymbolicLink(path)) throw new IOException("too many files or symlink");
                Path target = dest.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes[0] += Files.size(path); if (bytes[0] > LIMIT) throw new IOException("package too large");
                    Files.copy(path, target);
                } else throw new IOException("unsupported file type");
            }
        }
    }
    private static void unzip(Path zip, Path dest) throws IOException {
        long size = 0; int entries = 0;
        try (ZipFile archive = new ZipFile(zip.toFile())) { if (archive.size() == 0) throw new IOException("empty zip"); }
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 4096) throw new IOException("too many zip entries");
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest) || target.equals(dest)) throw new IOException("invalid zip path");
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) { size += count; if (size > LIMIT) throw new IOException("package too large"); output.write(buffer, 0, count); }
                    }
                }
            }
        }
        if (entries == 0) throw new IOException("empty or invalid zip");
    }

    private Running start(Path generation) throws Exception { return load(generation, true); }
    private Running load(Path generation, boolean start) throws Exception {
        Path snapshot = runtime.resolve(UUID.randomUUID().toString());
        ClassLoader loader = null;
        try {
            copyTree(generation, snapshot);
            Manifest manifest = Manifest.read(snapshot);
            RuntimeContext context = new RuntimeContext(manifest.id, snapshot.toFile());
            TakuPlugin plugin;
            if (manifest.provider) plugin = new ToolProvider(manifest, context, snapshot);
            else {
                loader = pluginLoader.create(snapshot.resolve(manifest.dex), snapshot.resolve(".optimized"));
                plugin = (TakuPlugin)loader.loadClass(manifest.className).getDeclaredConstructor().newInstance();
            }
            plugin.onLoad(context); if (start) plugin.onStart();
            return new Running(loader, pluginLoader, plugin, context, snapshot);
        } catch (Exception | LinkageError | AssertionError error) {
            if (loader != null) pluginLoader.close(loader); deleteTree(snapshot);
            throw new PluginException("LOAD_FAILED", error.toString(), error);
        }
    }

    public synchronized Map<String,Object> installUrl(String url, String expectedSha256, String signature, String publicKey) throws Exception { return audited("install-url", url, Map.of("url", url, "sha256", expectedSha256, "signed", signature != null && !signature.isBlank()), () -> installUrlInternal(url, expectedSha256, signature, publicKey)); }
    private Map<String,Object> installUrlInternal(String url, String expectedSha256, String signature, String publicKey) throws Exception {
        java.net.URI uri;
        try { uri = java.net.URI.create(url); }
        catch (IllegalArgumentException error) { throw new PluginException("INVALID_URL", "invalid URL", error); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new PluginException("INVALID_URL", "https URL without user info required");
        String expected = expectedSha256 == null ? "" : expectedSha256.toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new PluginException("INVALID_CHECKSUM", "sha256 must be 64 hexadecimal characters");
        Path download = Files.createTempFile(staging, ".remote-", ".zip");
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                if (status != 200) throw new PluginException("DOWNLOAD_FAILED", "HTTP " + status);
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(download, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > LIMIT) throw new PluginException("PACKAGE_TOO_LARGE", "remote package exceeds limit");
                        digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    }
                }
                StringBuilder actual = new StringBuilder();
                for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
                if (!actual.toString().equals(expected)) throw new PluginException("CHECKSUM_MISMATCH", "remote package checksum mismatch");
            } finally { connection.disconnect(); }
            verifyArchiveSignature(download, signature, publicKey);
            return installInternal(download);
        } finally { Files.deleteIfExists(download); }
    }

    static void verifyArchiveSignature(Path archive, String signature, String publicKey) throws Exception {
        boolean hasSignature = signature != null && !signature.isBlank();
        boolean hasKey = publicKey != null && !publicKey.isBlank();
        if (!hasSignature && !hasKey) return;
        if (!hasSignature || !hasKey || signature.length() > 4096 || publicKey.length() > 4096) throw new PluginException("INVALID_SIGNATURE", "signature and X.509 publicKey must be supplied together");
        try {
            java.security.PublicKey key = java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) verifier.update(buffer, 0, count);
            }
            if (!verifier.verify(Base64.getDecoder().decode(signature))) throw new PluginException("SIGNATURE_MISMATCH", "remote package signature mismatch");
        } catch (PluginException error) { throw error; }
        catch (Exception error) { throw new PluginException("INVALID_SIGNATURE", "cannot verify Ed25519 package signature", error); }
    }

    public synchronized Map<String,Object> install(Path source) throws Exception { return audited("install", source.toString(), Map.of("path", source.toString()), () -> installInternal(source)); }
    private Map<String,Object> installInternal(Path source) throws Exception {
        Path stage = staging.resolve(UUID.randomUUID().toString());
        Path destination = null;
        try {
            Files.createDirectories(stage);
            try { if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) copyTree(source, stage); else unzip(source, stage); }
            catch (IOException error) { throw new PluginException("INVALID_PACKAGE", error.getMessage(), error); }
            Manifest manifest = Manifest.read(stage);
            if (compareVersion((String)manifest.data.get("minHostVersion"), "1.0.0") > 0) throw new PluginException("HOST_VERSION", "requires host " + manifest.data.get("minHostVersion"));
            PluginRecord previous = records.get(manifest.id);
            if (previous != null) {
                Manifest current = Manifest.read(installed.resolve(previous.generation));
                int order = compareVersion(manifest.version, current.version);
                if (order == 0) throw new PluginException("ALREADY_INSTALLED", manifest.id + " " + manifest.version);
                if (order < 0) throw new PluginException("VERSION_DOWNGRADE", manifest.version + " < " + current.version);
            }
            String generation = UUID.randomUUID().toString();
            destination = installed.resolve(generation);
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            boolean enabled = previous == null || previous.enabled;
            if (previous != null) stop(previous);
            Running replacement = null;
            try {
                if (enabled) replacement = start(destination);
                PluginRecord next = new PluginRecord(manifest.id, generation, enabled, null, previous != null ? previous.generation : null);
                records.put(manifest.id, next);
                try { persist(); } catch (Exception error) { if (previous == null) records.remove(manifest.id); else records.put(manifest.id, previous); throw error; }
                if (replacement != null) running.put(manifest.id, replacement);
                destination = null;
                if (previous != null && previous.previousGeneration != null) try { deleteTree(installed.resolve(previous.previousGeneration)); } catch (IOException ignored) { }
                return show(manifest.id);
            } catch (Exception error) {
                if (replacement != null) replacement.close();
                if (previous != null && previous.enabled) {
                    try { running.put(previous.id, start(installed.resolve(previous.generation))); }
                    catch (Exception recovery) { previous.error = "rollback failed: " + recovery; persist(); }
                }
                throw error;
            }
        } finally { deleteTree(stage); if (destination != null) deleteTree(destination); }
    }

    private static int compareVersion(String left, String right) {
        String[] one = left.split("\\."), two = right.split("\\.");
        for (int index = 0; index < 3; index++) { int result = new java.math.BigInteger(one[index]).compareTo(new java.math.BigInteger(two[index])); if (result != 0) return result; }
        return 0;
    }
    private PluginRecord require(String id) throws PluginException { PluginRecord record = records.get(id); if (record == null) throw new PluginException("NOT_FOUND", id); return record; }
    private void stop(PluginRecord record) throws Exception {
        Running active = running.remove(record.id);
        if (active == null) return;
        try { active.close(); }
        catch (Exception error) {
            record.error = "stop failed: " + error;
            persist();
            throw new PluginException("STOP_FAILED", record.error, error);
        }
    }
    public synchronized List<Object> list() throws Exception {
        List<Object> result = new ArrayList<>();
        for (String id : records.keySet()) {
            Map<String,Object> record = show(id);
            Map<?,?> manifest = (Map<?,?>)record.get("manifest");
            Map<String,Object> summary = new LinkedHashMap<>();
            for (String key : List.of("id", "name", "description", "author", "category", "version", "type", "roles", "minHostVersion", "runtime")) if (manifest.containsKey(key)) summary.put(key, manifest.get(key));
            record.put("manifest", summary);
            record.remove("registrations");
            result.add(record);
        }
        return result;
    }
    public synchronized List<Object> tools() { return tools(null); }
    public synchronized List<Object> catalog() throws Exception {
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String,Running> entry : new TreeMap<>(running).entrySet()) {
            ToolProvider provider = entry.getValue().context.provider;
            if (provider == null || !provider.alive()) continue;
            List<Object> details = new ArrayList<>();
            for (Object item : provider.tools(null)) {
                if (!(item instanceof Map)) continue;
                Object name = ((Map<?,?>)item).get("id");
                if (name instanceof String) details.add(provider.tool((String)name));
            }
            result.add(Map.of("pluginId", entry.getKey(), "tools", details));
        }
        return result;
    }
    public synchronized List<Object> tools(String toolset) {
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String,Running> entry : new TreeMap<>(running).entrySet()) {
            ToolProvider provider = entry.getValue().context.provider;
            if (provider != null && provider.alive()) {
                List<Object> tools = provider.tools(toolset);
                if (toolset == null || !tools.isEmpty()) result.add(Map.of("pluginId", entry.getKey(), "tools", tools, "skills", provider.skills()));
            }
        }
        return result;
    }
    public synchronized Map<String,Object> tool(String id, String name) throws Exception {
        require(id);
        Running active = running.get(id);
        if (active == null || active.context.provider == null) throw new PluginException("PROVIDER_STOPPED", id);
        return active.context.provider.tool(name);
    }
    public synchronized List<Object> checks(String id) throws Exception {
        if (id != null) require(id);
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String,Running> entry : new TreeMap<>(running).entrySet()) {
            if (id != null && !id.equals(entry.getKey())) continue;
            ToolProvider provider = entry.getValue().context.provider;
            if (provider != null && provider.alive() && !provider.checks().isEmpty()) result.add(Map.of("pluginId", entry.getKey(), "checks", provider.checks()));
        }
        return result;
    }
    public synchronized Object runCheck(String id, String name, Map<String,Object> context) throws Exception { return audited("run-check", id, Map.of("name", name, "arguments", context), () -> runCheckInternal(id, name, context)); }
    private Object runCheckInternal(String id, String name, Map<String,Object> context) throws Exception {
        require(id);
        Running active = running.get(id);
        if (active == null || active.context.provider == null) throw new PluginException("PROVIDER_STOPPED", id);
        return active.context.provider.runCheck(name, context);
    }
    public synchronized Object callTool(String id, String name, Map<String,Object> arguments) throws Exception { return audited("call-tool", id, Map.of("name", name, "arguments", arguments), () -> callToolInternal(id, name, arguments)); }
    private Object callToolInternal(String id, String name, Map<String,Object> arguments) throws Exception {
        require(id);
        Running active = running.get(id);
        if (active == null || active.context.provider == null) throw new PluginException("PROVIDER_STOPPED", id);
        return active.context.provider.call(name, arguments);
    }
    public synchronized Map<String,Object> show(String id) throws Exception {
        PluginRecord record = require(id);
        Map<String,Object> result = new LinkedHashMap<>(record.json());
        result.put("manifest", Manifest.read(installed.resolve(record.generation)).data);
        Running active = running.get(id);
        result.put("state", active != null ? "started" : record.error != null ? "error" : "stopped");
        result.put("registrations", active == null ? Map.of("ui", Map.of(), "settings", Map.of(), "capabilities", List.of()) : active.context.snapshot());
        return result;
    }
    public synchronized Map<String,Object> enable(String id) throws Exception { return audited("enable", id, Map.of(), () -> enableInternal(id)); }
    private Map<String,Object> enableInternal(String id) throws Exception {
        PluginRecord record = require(id);
        if (record.enabled && running.containsKey(id)) return show(id);
        Running active = start(installed.resolve(record.generation));
        boolean prior = record.enabled; String oldError = record.error;
        record.enabled = true; record.error = null;
        try { persist(); } catch (Exception error) { record.enabled = prior; record.error = oldError; active.close(); throw error; }
        running.put(id, active); return show(id);
    }
    public synchronized Map<String,Object> disable(String id) throws Exception { return audited("disable", id, Map.of(), () -> disableInternal(id)); }
    private Map<String,Object> disableInternal(String id) throws Exception {
        PluginRecord record = require(id);
        if (!record.enabled && !running.containsKey(id)) return show(id);
        stop(record);
        boolean prior = record.enabled; String oldError = record.error;
        record.enabled = false; record.error = null;
        try { persist(); } catch (Exception error) { record.enabled = prior; record.error = oldError; if (prior) running.put(id, start(installed.resolve(record.generation))); throw error; }
        return show(id);
    }
    public synchronized Map<String,Object> reload(String id) throws Exception { return audited("reload", id, Map.of(), () -> reloadInternal(id)); }
    private Map<String,Object> reloadInternal(String id) throws Exception {
        PluginRecord record = require(id);
        if (!record.enabled) throw new PluginException("DISABLED", id);
        Running old = running.get(id);
        Path backup = null;
        if (old != null) {
            backup = runtime.resolve(UUID.randomUUID().toString()); copyTree(old.snapshot, backup); stop(record);
        }
        try {
            running.put(id, start(installed.resolve(record.generation)));
            record.error = null; persist(); return show(id);
        } catch (Exception error) {
            record.error = "reload failed: " + error;
            if (backup != null) try { running.put(id, start(backup)); } catch (Exception recovery) { record.error += "; recovery failed: " + recovery; }
            persist(); throw new PluginException("RELOAD_FAILED", record.error, error);
        } finally { if (backup != null) deleteTree(backup); }
    }
    public synchronized Map<String,Object> rollback(String id) throws Exception { return audited("rollback", id, Map.of(), () -> rollbackInternal(id)); }
    private Map<String,Object> rollbackInternal(String id) throws Exception {
        PluginRecord record = require(id);
        if (record.previousGeneration == null) throw new PluginException("NO_ROLLBACK", id);
        Path currentPath = installed.resolve(record.generation);
        Path targetPath = installed.resolve(record.previousGeneration);
        if (!Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) throw new PluginException("NOT_FOUND", "previous generation " + record.previousGeneration);
        Running old = running.remove(id);
        if (old != null) old.close();
        Running replacement = null;
        try {
            if (record.enabled) replacement = start(targetPath);
            PluginRecord next = new PluginRecord(id, record.previousGeneration, record.enabled, null, record.generation);
            records.put(id, next);
            try { persist(); }
            catch (Exception error) {
                records.put(id, record);
                if (replacement != null) replacement.close();
                if (record.enabled) running.put(id, start(currentPath));
                throw error;
            }
            if (replacement != null) running.put(id, replacement);
            return show(id);
        } catch (Exception error) {
            if (records.get(id) != record) records.put(id, record);
            if (!running.containsKey(id) && record.enabled) try { running.put(id, start(currentPath)); }
            catch (Exception recovery) { record.error = "rollback failed: " + recovery; try { persist(); } catch (Exception ignored) { } }
            throw error;
        }
    }

    public synchronized Map<String,Object> remove(String id) throws Exception {
        PluginRecord record = require(id);
        Running active = running.remove(id);
        boolean wasStarted = active != null;
        String warning = null;
        try {
            if (active == null) active = load(installed.resolve(record.generation), false);
            try { if (wasStarted) active.plugin.onStop(); active.plugin.onUninstall(); }
            finally { if (active.loader != null) active.factory.close(active.loader); deleteTree(active.snapshot); }
        } catch (Exception | AssertionError | LinkageError error) {
            warning = "uninstall callback failed: " + error;
        }
        records.remove(id);
        try { persist(); } catch (Exception error) { records.put(id, record); if (record.enabled) running.put(id, start(installed.resolve(record.generation))); throw error; }
        for (String generation : new String[] {record.generation, record.previousGeneration}) if (generation != null) try { deleteTree(installed.resolve(generation)); }
        catch (IOException error) { warning = "generation cleanup failed: " + error; }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("removed", true); if (warning != null) result.put("warning", warning);
        return result;
    }
    public synchronized void close() throws Exception {
        Exception failure = null;
        for (Running active : running.values()) try { active.close(); } catch (Exception error) { failure = error; }
        running.clear(); lock.release(); lockChannel.close();
        if (failure != null) throw failure;
    }
}
