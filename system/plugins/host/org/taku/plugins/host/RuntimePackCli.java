package org.taku.plugins.host;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class RuntimePackCli {
    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Path stage = null;
        boolean failed = false;
        try {
            if (args.length != 4) throw new IllegalArgumentException("usage: RuntimePackCli ZIP LOCK LOCK_SHA256 DEST");
            byte[] lockBytes = Files.readAllBytes(Path.of(args[1]));
            if (!sha(lockBytes).equals(args[2])) throw new IllegalArgumentException("lock checksum mismatch");
            Map<String,Object> lock = (Map<String,Object>)Json.parse(new String(lockBytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!"android-arm64".equals(lock.get("platform")) || !Set.of("22.23.4", "24.22.2").contains(lock.get("node")) || !"6.8.0".equals(lock.get("rea")) || !Set.of("17", "21").contains(lock.get("java")) || !"0.7.1".equals(lock.get("jadx"))) throw new IllegalArgumentException("unsupported runtime versions or platform");
            MessageDigest archiveDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) archiveDigest.update(buffer, 0, count);
            }
            StringBuilder archiveHash = new StringBuilder();
            for (byte value : archiveDigest.digest()) archiveHash.append(String.format("%02x", value & 255));
            if (!archiveHash.toString().equals(lock.get("archiveSha256"))) throw new IllegalArgumentException("archive checksum mismatch");
            Map<String,Object> files = (Map<String,Object>)lock.get("files");
            if (files == null || !files.keySet().containsAll(Set.of("bin/node", "bin/java", "rea/package.json", "rea/scripts/rea.mjs", "lib/jadx-headless-mcp-0.7.1-all.jar"))) throw new IllegalArgumentException("required runtime files missing from lock");
            Map<String,Object> links = lock.get("links") instanceof Map ? (Map<String,Object>)lock.get("links") : Map.of();
            if (links.size() > 4096) throw new IllegalArgumentException("too many runtime links");
            Set<String> executables = new HashSet<>();
            if (lock.get("executables") instanceof List) for (Object item : (List<?>)lock.get("executables")) {
                if (!(item instanceof String) || !files.containsKey(item)) throw new IllegalArgumentException("invalid executable path");
                executables.add((String)item);
            }
            boolean legacyExecutables = !lock.containsKey("executables");
            Path destination = Path.of(args[3]).toAbsolutePath().normalize();
            if (Files.exists(destination)) throw new IllegalArgumentException("destination already exists");
            Files.createDirectories(destination.getParent());
            stage = Files.createTempDirectory(destination.getParent(), ".rea-stage-");
            Set<String> seen = new HashSet<>();
            try (ZipFile archive = new ZipFile(args[0])) {
                Enumeration<? extends ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName(); Path path = stage.resolve(name).normalize();
                    if (!path.startsWith(stage) || name.startsWith("/") || !files.containsKey(name) || !seen.add(name)) throw new IllegalArgumentException("unexpected archive path: " + name);
                    byte[] contents;
                    try (InputStream input = archive.getInputStream(entry)) { contents = input.readNBytes(268435457); }
                    if (contents.length > 268435456 || !sha(contents).equals(files.get(name))) throw new IllegalArgumentException("file checksum mismatch or oversized: " + name);
                    Files.createDirectories(path.getParent()); Files.write(path, contents, StandardOpenOption.CREATE_NEW);
                    if ((legacyExecutables ? name.startsWith("bin/") : executables.contains(name)) && !path.toFile().setExecutable(true, false)) throw new IOException("cannot mark executable: " + name);
                }
            }
            if (!seen.equals(files.keySet())) throw new IllegalArgumentException("archive is missing locked files");
            for (Map.Entry<String,Object> item : links.entrySet()) {
                String name = item.getKey(); Object rawTarget = item.getValue();
                if (!(rawTarget instanceof String) || ((String)rawTarget).isEmpty()) throw new IllegalArgumentException("invalid link target: " + name);
                String target = (String)rawTarget; Path path = stage.resolve(name).normalize();
                if (!path.startsWith(stage) || name.startsWith("/") || Path.of(target).isAbsolute()) throw new IllegalArgumentException("unsafe link: " + name);
                Path resolved = path.getParent().resolve(target).normalize();
                if (!resolved.startsWith(stage) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("unsafe or duplicate link: " + name);
                Files.createDirectories(path.getParent());
                Files.createSymbolicLink(path, Path.of(target));
            }
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE); stage = null;
            System.out.println(Json.write(Map.of("ok", true, "root", destination.toString(), "files", seen.size(), "links", links.size())));
        } catch (Exception error) {
            System.out.println(Json.write(Map.of("ok", false, "error", String.valueOf(error.getMessage()))));
            failed = true;
        } finally {
            if (stage != null) try (java.util.stream.Stream<Path> paths = Files.walk(stage)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } catch (IOException ignored) { }
        }
        if (failed) System.exit(1);
    }
}
