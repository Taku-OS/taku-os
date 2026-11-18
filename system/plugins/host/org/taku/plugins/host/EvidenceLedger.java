package org.taku.plugins.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class EvidenceLedger {
    private final Path directory;

    EvidenceLedger(Path root) throws IOException {
        directory = root.resolve("evidence");
        Files.createDirectories(directory);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String,Object> sorted = new TreeMap<>();
            ((Map<?,?>)value).forEach((key, item) -> {
                if (!(key instanceof String)) throw new IllegalArgumentException("non-string key");
                sorted.put((String)key, canonical(item));
            });
            return sorted;
        }
        if (value instanceof Collection) {
            List<Object> items = new ArrayList<>();
            for (Object item : (Collection<?>)value) items.add(canonical(item));
            return items;
        }
        if (value instanceof Double && !Double.isFinite((Double)value)) throw new IllegalArgumentException("non-finite number");
        return value;
    }

    static String id(Map<String,Object> content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(content)).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static String targetHash(Path path) throws Exception {
        List<Object> files = new ArrayList<>();
        long total = 0;
        List<Path> paths;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(path)) { paths = stream.sorted().collect(java.util.stream.Collectors.toList()); }
        } else paths = List.of(path);
        for (Path item : paths) {
            if (Files.isSymbolicLink(item)) throw new IOException("symbolic link in target");
            if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) throw new IOException("target file unavailable");
            total += Files.size(item);
            if (total > 128L * 1024 * 1024) throw new IOException("target too large");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(item)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            files.add(Map.of("path", Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path.relativize(item).toString().replace('\\', '/') : "", "sha256", hex.toString()));
        }
        return id(Map.of("files", files));
    }

    synchronized String append(Map<String,Object> content) throws Exception {
        if (content.containsKey("id")) throw new IllegalArgumentException("id is computed");
        String id = id(content);
        Path path = directory.resolve(id + ".json");
        if (Files.exists(path)) { read(id); return id; }
        Map<String,Object> record = new LinkedHashMap<>(content);
        record.put("id", id);
        Path temporary = Files.createTempFile(directory, ".evidence-", ".tmp");
        try {
            Files.write(temporary, Json.write(canonical(record)).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return id;
    }

    @SuppressWarnings("unchecked")
    synchronized Map<String,Object> read(String id) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) throw new PluginException("USAGE", "invalid evidence id");
        Path path = directory.resolve(id + ".json");
        if (!Files.isRegularFile(path)) throw new PluginException("NOT_FOUND", "evidence " + id);
        try {
            Object parsed = Json.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("record must be object");
            Map<String,Object> record = new LinkedHashMap<>((Map<String,Object>)parsed);
            Object storedId = record.remove("id");
            if (!id.equals(storedId) || !id.equals(id(record))) throw new IllegalArgumentException("hash mismatch");
            record.put("id", id);
            return record;
        } catch (IllegalArgumentException error) {
            throw new PluginException("CORRUPT_EVIDENCE", "invalid evidence " + id + ": " + error.getMessage(), error);
        }
    }

    synchronized List<Object> list() throws Exception {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.json")) throw new PluginException("CORRUPT_EVIDENCE", "invalid evidence filename");
                ids.add(name.substring(0, 64));
            }
        }
        Collections.sort(ids);
        List<Object> records = new ArrayList<>();
        for (String id : ids) records.add(read(id));
        return records;
    }
}
