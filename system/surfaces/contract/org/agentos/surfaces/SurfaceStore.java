package org.agentos.surfaces;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** In-memory display lifecycle; accessed on the host's main thread. */
public final class SurfaceStore {
    public record Key(int owner, String id) {}
    public static final class Entry {
        public final Key key; public final SurfaceSpec spec; public final long expiresAt;
        public boolean selected;
        Entry(Key key, SurfaceSpec spec, long expiresAt) { this.key=key; this.spec=spec; this.expiresAt=expiresAt; }
    }
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    public Entry publish(int owner, SurfaceSpec spec, long now) {
        Key key = new Key(owner, spec.id()); Entry previous = entries.get(key);
        if (previous != null && spec.revision() <= previous.spec.revision())
            throw new IllegalArgumentException("Stale revision");
        if (previous == null && entries.size() >= 8) throw new IllegalArgumentException("Surface capacity reached");
        Entry entry = new Entry(key, spec, now + spec.ttlMs()); entries.put(key, entry); return entry;
    }
    public boolean select(Key key, long revision, String action) {
        Entry entry = entries.get(key);
        if (entry == null || entry.selected || entry.spec.revision() != revision
                || entry.spec.actions().stream().noneMatch(a -> a.id().equals(action))) return false;
        entry.selected = true; return true;
    }
    public Entry remove(Key key) { return entries.remove(key); }
    public List<Entry> expire(long now) {
        List<Entry> expired = new ArrayList<>();
        entries.values().removeIf(entry -> { if (entry.expiresAt <= now) { expired.add(entry); return true; } return false; });
        return expired;
    }
    public List<Entry> entries() { return List.copyOf(entries.values()); }
    public Entry get(Key key) { return entries.get(key); }
    public void clear() { entries.clear(); }
}
