// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-owned BYOK credential. Never serialized into business/API state. */
public final class FalKeyStore {
    private static final String ALIAS = "tryon-fal-v1";
    private final SharedPreferences prefs;
    public FalKeyStore(Context context) { prefs = context.getSharedPreferences("private-credentials", Context.MODE_PRIVATE); }
    private SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public synchronized boolean hasKey() { return prefs.contains("ciphertext"); }
    public synchronized void set(String value) throws Exception {
        String key = value == null ? "" : value.trim();
        if (key.length() < 8 || key.length() > 1024 || !key.matches("[A-Za-z0-9_:.-]+")) throw new IllegalArgumentException("请检查 fal Key 的格式");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret());
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存凭据");
    }
    public synchronized String get() throws Exception {
        if (!hasKey()) throw new IllegalStateException("请先配置你自己的 fal Key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("ciphertext", ""), Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    public synchronized void clear() throws java.io.IOException {
        if (!prefs.edit().clear().commit()) throw new java.io.IOException("无法移除凭据，请重试");
    }
}
