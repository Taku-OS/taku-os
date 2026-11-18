package org.agentos.assistant;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only preview identity checks, kept independent of Android views. */
final class ChatWorkspacePreviewProtocol {
    static final int MAX_SCREENS = 3;
    static final int MAX_FRAME_BYTES = 4_000_000;
    static final long CATALOG_MAX_AGE_MS = 5000;

    private ChatWorkspacePreviewProtocol() {}

    /** One real catalog response, with its original monotonic verification time. */
    static final class CatalogSnapshot {
        private final JSONObject catalog;
        final long checkedAt;

        CatalogSnapshot(JSONObject catalog, long checkedAt, long now) throws Exception {
            this.checkedAt = checkedAt;
            requireFresh(now);
            this.catalog = new JSONObject(catalog.toString());
            if (!"taku.workspaces/1".equals(this.catalog.getString("schema")))
                throw new IOException("INVALID_WORKSPACE_CATALOG");
            this.catalog.getJSONArray("screens");
        }

        private void requireFresh(long now) throws IOException {
            if (checkedAt <= 0 || checkedAt > now || now - checkedAt > CATALOG_MAX_AGE_MS)
                throw new IOException("CATALOG_EXPIRED");
        }

        JSONArray select(String thread, long now) throws Exception {
            requireFresh(now);
            return ChatWorkspacePreviewProtocol.select(catalog, thread);
        }

        JSONObject exact(String thread, String key, String generation, long now) throws Exception {
            JSONArray screens = select(thread, now);
            for (int i = 0; i < screens.length(); i++) {
                JSONObject screen = screens.getJSONObject(i);
                if (key.equals(screen.optString("screenKey"))
                        && generation.equals(screen.optString("generation"))) return screen;
            }
            throw new IOException("SCREEN_LEASE_CHANGED");
        }
    }

    /** Shares the owner's verified catalog with frame reads; never refreshes it itself. */
    static final class VerifiedCatalog {
        private CatalogSnapshot current;
        private long requestRevision, epoch;

        static final class Lease {
            final JSONObject binding;
            private final CatalogSnapshot source;
            private final long epoch;
            private final String thread, key, generation;

            private Lease(CatalogSnapshot source, long epoch, String thread, String key,
                    String generation, JSONObject binding) {
                this.source = source; this.epoch = epoch; this.thread = thread;
                this.key = key; this.generation = generation; this.binding = binding;
            }
        }

        synchronized long beginRead() { return ++requestRevision; }

        synchronized boolean publish(long revision, CatalogSnapshot snapshot) {
            if (revision != requestRevision) return false;
            current = snapshot;
            return true;
        }

        synchronized void failedRead(long revision) {
            if (revision == requestRevision) invalidate();
        }

        synchronized void invalidate() {
            current = null; requestRevision++; epoch++;
        }

        synchronized Lease lease(String thread, String key, String generation, long now) throws Exception {
            if (current == null) throw new IOException("CATALOG_UNAVAILABLE");
            return new Lease(current, epoch, thread, key, generation,
                    current.exact(thread, key, generation, now));
        }

        synchronized JSONObject recheck(Lease lease, long now) throws Exception {
            if (lease.epoch != epoch || current == null) throw new IOException("CATALOG_CHANGED");
            // A newer catalog cannot make a frame that started under an expired snapshot fresh.
            lease.source.exact(lease.thread, lease.key, lease.generation, now);
            JSONObject next = current.exact(lease.thread, lease.key, lease.generation, now);
            if (next.getInt("displayId") != lease.binding.getInt("displayId"))
                throw new IOException("SCREEN_LEASE_CHANGED");
            return next;
        }

        synchronized void failedLease(Lease lease) {
            // A late failure must not discard a more recently verified response.
            // A real catalog read already in flight can still establish a new snapshot.
            if (lease.epoch == epoch && current == lease.source) { current = null; epoch++; }
        }
    }

    static boolean validThread(String thread) {
        return thread != null && thread.matches("[a-f0-9-]{36}");
    }

    static JSONArray select(JSONObject catalog, String thread) throws Exception {
        if (!validThread(thread)) return new JSONArray();
        return selectMatching(catalog, thread);
    }

    static JSONArray selectHome(JSONObject catalog) throws Exception {
        return selectMatching(catalog, null);
    }

    private static JSONArray selectMatching(JSONObject catalog, String thread) throws Exception {
        if (!"taku.workspaces/1".equals(catalog.getString("schema")))
            throw new IOException("INVALID_WORKSPACE_CATALOG");
        boolean home = thread == null;
        JSONArray selected = new JSONArray();
        JSONArray screens = catalog.getJSONArray("screens");
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < screens.length(); i++) {
            JSONObject screen = screens.getJSONObject(i);
            String owner = screen.optString("threadId");
            // Session reads use the exact thread. Home reads use the broker's eligibility flag.
            if (home ? !screen.optBoolean("homePreviewEligible") : !thread.equals(owner)) continue;
            String key = screen.optString("screenKey");
            String generation = screen.optString("generation");
            String state = screen.optString("status");
            if (!validThread(owner) || !key.matches("task-[a-f0-9]{20}") || !validThread(generation)
                    || screen.optInt("displayId", -1) <= 0)
                throw new IOException("INVALID_SCREEN_LEASE");
            if (!keys.add(key)) throw new IOException("DUPLICATE_SCREEN_LEASE");
            if (!screen.optBoolean("admitted") || state.equals("unavailable")
                    || state.equals("stale") || state.equals("disconnected")
                    || state.equals("rebind_required")) continue;
            if (home || selected.length() < MAX_SCREENS)
                selected.put(new JSONObject(screen.toString()));
        }
        return selected;
    }

    static void validateHost(JSONObject binding, JSONObject meta, int peerUid, int peerPid)
            throws Exception {
        String key = binding.getString("screenKey");
        String generation = binding.getString("generation");
        if ((peerUid != 0 && peerUid != 1000 && peerUid != 2000) || peerPid <= 0
                || peerUid != meta.getInt("uid") || peerPid != meta.getInt("pid")
                || binding.getInt("displayId") <= 0
                || binding.getInt("displayId") != meta.getInt("displayId")
                || !key.equals(meta.getString("screenKey"))
                || !generation.equals(meta.getString("generation"))
                || !("taku-mirror-" + key).equals(meta.getString("mirrorSocket"))
                || !("Taku-Independent-Screen-" + key).equals(meta.getString("name"))
                || !("/data/local/agentos/virtual-display/screens/" + key)
                        .equals(meta.getString("stateDirectory")))
            throw new IOException("SCREEN_HOST_IDENTITY");
        int width = meta.getInt("width"), height = meta.getInt("height");
        if (width < 1 || height < 1 || width > 4096 || height > 4096)
            throw new IOException("INVALID_SCREEN_SIZE");
    }

    static String identity(JSONObject screen) {
        return screen.optString("threadId") + "/" + screen.optString("screenKey")
                + "/" + screen.optString("generation") + "/" + screen.optInt("displayId");
    }
}
