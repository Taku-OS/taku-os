package org.agentos.assistant;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Uses a disposable Android Keystore alias and synthetic key material only. */
public final class JevCredentialStoreTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "taku-agent-jev-store-test");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey testKey = generator.generateKey();
        JevCredentialStore.KeyProvider provider = new JevCredentialStore.KeyProvider() {
            private SecretKey key = testKey;
            @Override public SecretKey getOrCreate() { return key; }
            @Override public void delete() { key = null; }
        };
        JevCredentialStore store = new JevCredentialStore(directory, provider);
        String syntheticKey = "sk-or-v1-0123456789abcdefghijklmnopqr";
        try {
            check(!store.configured(), "store must start empty");
            store.save(syntheticKey);
            check(store.configured(), "saved key must be configured");
            check(syntheticKey.equals(store.read()), "encrypted key must round-trip");
            byte[] ciphertext = Files.readAllBytes(new File(directory, "jev-openrouter.enc").toPath());
            check(!new String(ciphertext, StandardCharsets.ISO_8859_1).contains(syntheticKey),
                    "plaintext key must not appear in stored file");
            store.save("sk-or-v1-zyxwvutsrqponmlkjihgfedcba987654");
            check("sk-or-v1-zyxwvutsrqponmlkjihgfedcba987654".equals(store.read()),
                    "replacement must preserve readable credential");
            store.clear();
            check(!store.configured(), "clear must remove stored key");
            System.out.println("JevCredentialStoreTest: PASS");
        } finally {
            store.clear();
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
