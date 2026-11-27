package org.agentos.assistant;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Host test for TakuAccountStore AES-GCM encryption, atomic replace, and secure wipe. */
public final class TakuAccountStoreTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("taku-account-store-test").toFile();
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();

        TakuAccountStore.KeyProvider keyProvider = new TakuAccountStore.KeyProvider() {
            private SecretKey current = key;

            @Override
            public SecretKey getOrCreate() {
                if (current == null) current = key;
                return current;
            }

            @Override
            public void delete() {
                current = null;
            }
        };

        TakuAccountStore store = new TakuAccountStore(dir, keyProvider);
        TakuAccountStore.Session first =
                new TakuAccountStore.Session(
                        "jwt.header.payload.sig-first",
                        "refresh-token-first",
                        1_800_000_000_000L,
                        "user-1",
                        "alice@example.com",
                        1_700_000_000_000L);
        try {
            check(!store.exists(), "store must start empty");
            store.save(first);
            check(store.exists(), "saved session must exist");

            byte[] encryptedBytes = Files.readAllBytes(new File(dir, "session.enc").toPath());
            String rawIso = new String(encryptedBytes, StandardCharsets.ISO_8859_1);
            check(!rawIso.contains(first.accessToken), "access token must never appear plaintext in ciphertext");
            check(!rawIso.contains(first.refreshToken), "refresh token must never appear plaintext in ciphertext");
            check(!rawIso.contains(first.email), "email must stay encrypted inside ciphertext");

            TakuAccountStore.Session roundTrip = store.read();
            check(first.accessToken.equals(roundTrip.accessToken), "access token round-trip");
            check(first.refreshToken.equals(roundTrip.refreshToken), "refresh token round-trip");
            check(first.userId.equals(roundTrip.userId), "userId round-trip");
            check(first.email.equals(roundTrip.email), "email round-trip");
            check(first.expiresAtMs == roundTrip.expiresAtMs, "expiry round-trip");

            TakuAccountStore.Session second =
                    new TakuAccountStore.Session(
                            "jwt.header.payload.sig-second",
                            "refresh-token-second",
                            1_900_000_000_000L,
                            "user-1",
                            "alice@example.com",
                            1_700_000_100_000L);
            store.save(second);
            TakuAccountStore.Session replaced = store.read();
            check(second.accessToken.equals(replaced.accessToken), "atomic replace access token");
            check(second.refreshToken.equals(replaced.refreshToken), "atomic replace refresh token");

            store.clear();
            check(!store.exists(), "clear must delete session.enc");
            check(!new File(dir, "session.enc.tmp").exists(), "temp file must not remain");
            System.out.println("TakuAccountStoreTest: PASS");
        } finally {
            store.clear();
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
