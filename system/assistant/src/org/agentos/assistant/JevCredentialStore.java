package org.agentos.assistant;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Legacy migration compatibility; new credentials are managed by phone-local Magpie.
 * Stores the OpenRouter key only in app-private storage, encrypted by a device-local Keystore key. */
final class JevCredentialStore {
    private static final String ALIAS = "taku-agent-jev-openrouter-v1";
    private static final String FILE_NAME = "jev-openrouter.enc";
    private final File directory;
    private final KeyProvider keyProvider;

    interface KeyProvider {
        SecretKey getOrCreate() throws Exception;
        void delete() throws Exception;
    }

    JevCredentialStore(File directory) {
        this(directory, new AndroidKeyProvider(ALIAS));
    }

    JevCredentialStore(File directory, KeyProvider keyProvider) {
        this.directory = directory;
        this.keyProvider = keyProvider;
    }

    synchronized boolean configured() {
        return credentialFile().isFile();
    }

    synchronized void save(String apiKey) throws Exception {
        if (apiKey == null || !apiKey.trim().matches("sk-or-v1-[A-Za-z0-9_-]{20,}"))
            throw new IllegalArgumentException("Enter a valid OpenRouter API Key");
        byte[] clear = apiKey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            encrypted = cipher.doFinal(clear);
            File temp = new File(directory, FILE_NAME + ".tmp");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create private settings directory");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(iv);
                output.write(encrypted);
                output.getFD().sync();
            }
            temp.setReadable(false, false);
            temp.setWritable(false, false);
            temp.setReadable(true, true);
            temp.setWritable(true, true);
            File target = credentialFile();
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(clear, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    synchronized String read() throws Exception {
        File file = credentialFile();
        if (!file.isFile()) throw new IllegalStateException("Jev key is not configured");
        byte[] blob = readAll(file, 16384);
        if (blob.length <= 12) throw new IllegalStateException("Stored Jev credential is invalid");
        byte[] iv = Arrays.copyOfRange(blob, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(blob, 12, blob.length);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            clear = cipher.doFinal(ciphertext);
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(blob, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    synchronized void clear() throws Exception {
        File file = credentialFile();
        if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot remove local credential");
        keyProvider.delete();
    }

    private SecretKey key() throws Exception {
        return keyProvider.getOrCreate();
    }

    private static final class AndroidKeyProvider implements KeyProvider {
        private final String alias;

        AndroidKeyProvider(String alias) { this.alias = alias; }

        @Override public SecretKey getOrCreate() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }

        @Override public void delete() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) store.deleteEntry(alias);
        }
    }

    private File credentialFile() { return new File(directory, FILE_NAME); }

    private static byte[] readAll(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IllegalStateException("Stored Jev credential is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
