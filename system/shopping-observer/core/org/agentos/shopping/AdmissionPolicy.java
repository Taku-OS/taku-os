// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Immutable, target-bound one-use admissions. The Android adapter supplies actual Binder identity. */
public final class AdmissionPolicy {
    public static final long TTL_MS = 30_000;
    public record Entry(String token, String requestId, String reference, String sourcePackage,
                        int sourceWindow, String roomKey, int targetUid, String targetSigner,
                        String category, String prompt, long issuedAt, long expiresAt) {
        public Entry {
            if (!nonce(token) || requestId == null || !requestId.matches("[0-9a-f-]{36}")
                    || reference == null || reference.isBlank() || sourcePackage == null || sourcePackage.isBlank()
                    || sourceWindow < 0 || roomKey == null || roomKey.isBlank() || targetUid < 0
                    || targetSigner == null || targetSigner.isBlank() || !"upper_body".equals(category)
                    || prompt == null || prompt.isBlank() || prompt.length() > 2000
                    || issuedAt < 0 || issuedAt > Long.MAX_VALUE - TTL_MS || expiresAt != issuedAt + TTL_MS)
                throw new IllegalArgumentException("invalid_admission");
        }
    }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    public synchronized void issue(Entry entry, long now) {
        Objects.requireNonNull(entry); purge(now);
        if (now != entry.issuedAt() || entries.containsKey(entry.token())) throw new IllegalArgumentException("invalid_issue");
        while (entries.size() >= 4) entries.remove(entries.keySet().iterator().next());
        entries.put(entry.token(), entry);
    }
    public synchronized Entry redeem(String token, String requestId, int uid, String signer, long now) {
        purge(now);
        if (!nonce(token)) return null;
        Entry entry = entries.get(token);
        if (entry == null || now < entry.issuedAt() || now >= entry.expiresAt()
                || uid != entry.targetUid() || !entry.targetSigner().equals(signer)
                || !entry.requestId().equals(requestId)) return null;
        entries.remove(token); return entry;
    }
    public synchronized void discard(String token) { entries.remove(token); }
    public synchronized void clear() { entries.clear(); }
    private void purge(long now) { entries.values().removeIf(e -> now < e.issuedAt() || now >= e.expiresAt()); }
    private static boolean nonce(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
}
