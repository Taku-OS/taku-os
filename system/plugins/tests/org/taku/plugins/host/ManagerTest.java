package org.taku.plugins.host;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
import java.security.*;

public final class ManagerTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void fails(String code, Task action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (PluginException error) { check(error.code.equals(code), "expected " + code + ", got " + error.code); }
    }
    interface Task { void run() throws Exception; }
    private static void copy(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> paths = Files.list(source)) {
            for (Path path : (Iterable<Path>)paths::iterator) Files.copy(path, target.resolve(path.getFileName()));
        }
    }
    private static void zip(Path source, Path output) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output)); java.util.stream.Stream<Path> paths = Files.list(source)) {
            for (Path path : (Iterable<Path>)paths::iterator) { zip.putNextEntry(new ZipEntry(path.getFileName().toString())); Files.copy(path, zip); zip.closeEntry(); }
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        check(BoundedLine.read(new ByteArrayInputStream("é\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 3).equals("é"), "UTF-8 and CRLF");
        ByteArrayInputStream oversized = new ByteArrayInputStream("éééé".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            BoundedLine.read(oversized, 3);
            throw new AssertionError("expected byte limit");
        } catch (IOException expected) {
            check(oversized.available() == 4, "reject before consuming the rest of an unterminated line");
        }
        Json.parse("{\"items\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}");
        try {
            Json.parse("{\"items\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}");
            throw new AssertionError("expected JSON depth limit");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("nesting"), "reject nested containers at the depth boundary");
        }
        Path base = Files.createTempDirectory("taku-manager-test-");
        Path root = base.resolve("state"), examples = Path.of(args[0]);
        String id = "org.example.settings";
        Path original = examples.resolve("settings-page");
        final int[] loads = {0};
        PluginLoader factory = new PluginLoader() {
            final JvmPluginLoader delegate = new JvmPluginLoader();
            public ClassLoader create(Path code, Path optimized) throws Exception { loads[0]++; return delegate.create(code, optimized); }
            public void close(ClassLoader loader) throws Exception { delegate.close(loader); }
        };
        Path signed = base.resolve("signed-package.zip");
        Files.writeString(signed, "signed package bytes");
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(Files.readAllBytes(signed));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        fails("UNTRUSTED_PUBLISHER", () -> PluginManager.requireTrustedPublisher(null, signature, publicKey));
        fails("UNTRUSTED_PUBLISHER", () -> PluginManager.requireTrustedPublisher("other-configured-key", signature, publicKey));
        fails("INVALID_SIGNATURE", () -> PluginManager.requireTrustedPublisher(publicKey, null, null));
        PluginManager.requireTrustedPublisher(publicKey, signature, null);
        PluginManager.requireTrustedPublisher(publicKey, signature, publicKey);
        PluginManager.verifyArchiveSignature(signed, signature, publicKey);
        Signature wrong = Signature.getInstance("Ed25519");
        wrong.initSign(keys.getPrivate());
        wrong.update("other bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String wrongSignature = Base64.getEncoder().encodeToString(wrong.sign());
        fails("SIGNATURE_MISMATCH", () -> PluginManager.verifyArchiveSignature(signed, wrongSignature, publicKey));
        fails("INVALID_SIGNATURE", () -> PluginManager.verifyArchiveSignature(signed, signature, ""));
        try (PluginManager manager = new PluginManager(root, factory)) {
            fails("LOCKED", () -> new PluginManager(root));
            Map<String,Object> record = manager.install(original);
            check(record.get("state").equals("started"), "install starts plugin");
            Map<String,Object> registrations = (Map<String,Object>)record.get("registrations");
            check(((Map<?,?>)registrations.get("ui")).containsKey("example.settings"), "UI registered");
            check(((Map<?,?>)registrations.get("settings")).containsKey("example.message"), "setting registered");
            fails("ALREADY_INSTALLED", () -> manager.install(original));
            Path badZip = base.resolve("bad.zip"); Files.writeString(badZip, "not a zip");
            fails("INVALID_PACKAGE", () -> manager.install(badZip));
            Path traversal = base.resolve("traversal.zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(traversal))) { output.putNextEntry(new ZipEntry("../escape")); output.write(1); output.closeEntry(); }
            fails("INVALID_PACKAGE", () -> manager.install(traversal));
            check(!Files.exists(base.resolve("escape")), "zip traversal rejected");
            Path invalid = base.resolve("invalid"); copy(original, invalid); Files.writeString(invalid.resolve("manifest.json"), "{}");
            fails("INVALID_MANIFEST", () -> manager.install(invalid));
            check(manager.disable(id).get("enabled").equals(false), "disable");
            check(manager.disable(id).get("enabled").equals(false), "disable idempotent");
            fails("DISABLED", () -> manager.reload(id));
            manager.enable(id); manager.enable(id);
            ExecutorService workers = Executors.newFixedThreadPool(6);
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) futures.add(workers.submit(() -> { try { manager.enable(id); } catch (Exception error) { throw new RuntimeException(error); } }));
            for (Future<?> future : futures) future.get();
            futures.clear();
            for (int index = 0; index < 8; index++) futures.add(workers.submit(() -> { try { manager.install(original); throw new AssertionError("duplicate accepted"); } catch (PluginException error) { if (!error.code.equals("ALREADY_INSTALLED")) throw new RuntimeException(error); } catch (Exception error) { throw new RuntimeException(error); } }));
            for (Future<?> future : futures) future.get();
            workers.shutdown();
            check(manager.list().size() == 1, "concurrent idempotence");
            Path dex = root.resolve("installed").resolve((String)record.get("generation")).resolve("plugin.dex");
            byte[] old = Files.readAllBytes(dex); Files.writeString(dex, "broken jar");
            fails("RELOAD_FAILED", () -> manager.reload(id));
            record = manager.show(id);
            check(record.get("state").equals("started") && ((String)record.get("error")).contains("reload failed"), "reload recovered old instance and retained error");
            Files.write(dex, old); int beforeReload = loads[0]; manager.reload(id);
            check(loads[0] == beforeReload + 1, "reload uses injected loader factory");
            Path upgrade = base.resolve("upgrade"); copy(original, upgrade);
            Path manifestPath = upgrade.resolve("manifest.json");
            Files.writeString(manifestPath, Files.readString(manifestPath).replace("1.0.0\",\"type", "1.1.0\",\"type"));
            Path archive = base.resolve("upgrade.zip"); zip(upgrade, archive);
            record = manager.install(archive);
            check(((Map<?,?>)record.get("manifest")).get("version").equals("1.1.0"), "zip upgrade");
            fails("VERSION_DOWNGRADE", () -> manager.install(original));
            Path badUpgrade = base.resolve("bad-upgrade"); copy(upgrade, badUpgrade);
            Path badManifest = badUpgrade.resolve("manifest.json");
            Files.writeString(badManifest, Files.readString(badManifest).replace("1.1.0\",\"type", "1.2.0\",\"type"));
            Files.writeString(badUpgrade.resolve("plugin.dex"), "bad");
            fails("LOAD_FAILED", () -> manager.install(badUpgrade));
            check(((Map<?,?>)manager.show(id).get("manifest")).get("version").equals("1.1.0") && manager.show(id).get("state").equals("started"), "upgrade rollback");
            record = manager.rollback(id);
            check(((Map<?,?>)record.get("manifest")).get("version").equals("1.0.0") && record.get("state").equals("started"), "explicit version rollback");
            record = manager.rollback(id);
            check(((Map<?,?>)record.get("manifest")).get("version").equals("1.1.0") && record.get("state").equals("started"), "rollback can swap versions again");
            manager.install(examples.resolve("status-bar"));
            manager.install(examples.resolve("launcher-grid"));
            check(((List<?>)((Map<?,?>)manager.show("org.example.statusbar").get("registrations")).get("capabilities")).contains("example.status-bar"), "capability registered");
            check(((Map<?,?>)((Map<?,?>)manager.show("org.example.launcher").get("registrations")).get("ui")).containsKey("example.grid"), "launcher registered");
        }
        try (PluginManager manager = new PluginManager(root)) {
            check(manager.list().size() == 3 && manager.show(id).get("state").equals("started"), "restart recovers persistent state");
            manager.remove(id);
            fails("NOT_FOUND", () -> manager.show(id));
        }
        try (PluginManager manager = new PluginManager(root)) { check(manager.list().size() == 2, "remove persisted"); }
        System.out.println("PASS: manifest, install, zip, upgrade recovery, explicit rollback, reload recovery, remove, concurrency and restart");
    }
}
