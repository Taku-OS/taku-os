package org.agentos.assistant;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Hardware-backed AES-256-GCM store for the Taku account session in CE private storage.
 * Mirrors JevCredentialStore without setUnlockedDeviceRequired(true) so background token
 * refreshes can run after first unlock (USER_UNLOCKED).
 */
final class TakuAccountStore {
    private static final String ALIAS = "taku-account-session-v1";
    private static final String FILE_NAME = "session.enc";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_BLOB_BYTES = 64 * 1024;

    interface KeyProvider {
        SecretKey getOrCreate() throws Exception;
        void delete() throws Exception;
    }

    static final class Session {
        final String accessToken;
        final String refreshToken;
        final long expiresAtMs;
        final String userId;
        final String email;
        final long updatedAtMs;

        Session(
                String accessToken,
                String refreshToken,
                long expiresAtMs,
                String userId,
                String email,
                long updatedAtMs) {
            if (isBlank(accessToken) || isBlank(refreshToken)) {
                throw new IllegalArgumentException("Session tokens must not be blank");
            }
            if (isBlank(userId) || isBlank(email)) {
                throw new IllegalArgumentException("Session identity must not be blank");
            }
            if (expiresAtMs <= 0L) {
                throw new IllegalArgumentException("Session expiry must be positive");
            }
            this.accessToken = accessToken.trim();
            this.refreshToken = refreshToken.trim();
            this.expiresAtMs = expiresAtMs;
            this.userId = userId.trim();
            this.email = email.trim();
            this.updatedAtMs = updatedAtMs;
        }

        private JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("accessToken", accessToken)
                    .put("refreshToken", refreshToken)
                    .put("expiresAtMs", expiresAtMs)
                    .put("userId", userId)
                    .put("email", email)
                    .put("updatedAtMs", updatedAtMs);
        }

        private static Session fromJson(JSONObject json) throws Exception {
            return new Session(
                    json.getString("accessToken"),
                    json.getString("refreshToken"),
                    json.getLong("expiresAtMs"),
                    json.getString("userId"),
                    json.getString("email"),
                    json.optLong("updatedAtMs", 0L));
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }

    private final File directory;
    private final KeyProvider keyProvider;

    TakuAccountStore(File filesDir) {
        this(new File(filesDir, "taku_account"), new AndroidKeyProvider(ALIAS));
    }

    TakuAccountStore(File directory, KeyProvider keyProvider) {
        this.directory = directory;
        this.keyProvider = keyProvider;
    }

    synchronized boolean exists() {
        return sessionFile().isFile();
    }

    synchronized void save(Session session) throws Exception {
        byte[] clear = session.toJson().toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new IllegalStateException("Unexpected GCM IV length");
            }
            encrypted = cipher.doFinal(clear);
            ensurePrivateDir();
            File temp = new File(directory, FILE_NAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(iv);
                output.write(encrypted);
                output.getFD().sync();
            }
            setOwnerOnlyFilePermissions(temp);
            File target = sessionFile();
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(clear, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    synchronized Session read() throws Exception {
        File file = sessionFile();
        if (!file.isFile()) {
            throw new IllegalStateException("Taku account session is not configured");
        }
        byte[] blob = readAll(file, MAX_BLOB_BYTES);
        if (blob.length <= IV_BYTES) {
            Arrays.fill(blob, (byte) 0);
            throw new IllegalStateException("Stored Taku session is invalid");
        }
        byte[] iv = Arrays.copyOfRange(blob, 0, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(blob, IV_BYTES, blob.length);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), new GCMParameterSpec(TAG_BITS, iv));
            clear = cipher.doFinal(ciphertext);
            return Session.fromJson(new JSONObject(new String(clear, StandardCharsets.UTF_8)));
        } finally {
            Arrays.fill(blob, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    synchronized void clear() throws Exception {
        File temp = new File(directory, FILE_NAME + ".tmp");
        if (temp.exists()) temp.delete();
        File file = sessionFile();
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Cannot remove stored Taku account session");
        }
        keyProvider.delete();
    }

    private void ensurePrivateDir() {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Cannot create private Taku account directory");
        }
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private static void setOwnerOnlyFilePermissions(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private File sessionFile() {
        return new File(directory, FILE_NAME);
    }

    private static byte[] readAll(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    throw new IllegalStateException("Stored Taku session is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class AndroidKeyProvider implements KeyProvider {
        private final String alias;

        AndroidKeyProvider(String alias) {
            this.alias = alias;
        }

        @Override
        public SecretKey getOrCreate() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) {
                return (SecretKey) store.getKey(alias, null);
            }
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build());
            return generator.generateKey();
        }

        @Override
        public void delete() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias);
            }
        }
    }
}
