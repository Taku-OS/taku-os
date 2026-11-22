// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** A bounded view of one visible TikTok live room. None of these keys is a TikTok room ID. */
public record LivePage(String packageName, int windowId, String hostKey, String hostName,
                       String productTitle, String contentKey) {
    public LivePage {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(hostKey);
        Objects.requireNonNull(hostName);
        Objects.requireNonNull(productTitle);
        Objects.requireNonNull(contentKey);
        if (windowId < 0 || hostKey.isEmpty() || hostName.isBlank() || contentKey.isEmpty())
            throw new IllegalArgumentException("Invalid live page");
    }

    public boolean hasProduct() { return !productTitle.isEmpty(); }

    public static LivePage of(String packageName, int windowId, String hostName, String productTitle) {
        String host = clean(hostName), product = clean(productTitle);
        String hostKey = packageName + ":" + digest(canonical(host));
        return new LivePage(packageName, windowId, hostKey, host, product,
            hostKey + ":" + digest(product.isEmpty() ? "room-without-product" : canonical(product)));
    }

    static String clean(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
    }

    static String canonical(String value) { return clean(value).toLowerCase(Locale.ROOT); }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : bytes) out.append(String.format(Locale.ROOT, "%02x", item & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
