package org.agentos.assistant;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only boundary checks; these do not claim Android socket/lifecycle acceptance. */
public final class ChatWorkspacePreviewProtocolTest {
    private static final String THREAD = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String OTHER = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final String GENERATION = "11111111-2222-3333-4444-555555555555";
    private static int checks;
    interface Operation { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        JSONObject own = binding(THREAD, 1);
        JSONArray selected = ChatWorkspacePreviewProtocol.select(catalog(binding(OTHER, 2), own), THREAD);
        check("Only the exact conversation is selected", selected.length() == 1
                && selected.getJSONObject(0).getString("threadId").equals(THREAD));
        selected.getJSONObject(0).put("packageName", "changed.by.consumer");
        check("Consumer mutation cannot alter the catalog", own.getString("packageName").equals("org.example.app1"));
        check("No implicit default screen", ChatWorkspacePreviewProtocol.select(catalog(own), "").length() == 0);
        check("Other conversation stays empty", ChatWorkspacePreviewProtocol.select(catalog(own), OTHER).length() == 0);
        check("At most three exact-thread screens", ChatWorkspacePreviewProtocol.select(catalog(
                own, binding(THREAD, 2), binding(THREAD, 3), binding(THREAD, 4)), THREAD).length() == 3);
        for (String state : new String[]{"unavailable", "stale", "disconnected", "rebind_required"}) {
            JSONObject stale = binding(THREAD, 1).put("status", state);
            check("Clear " + state + " lease", ChatWorkspacePreviewProtocol.select(catalog(stale), THREAD).length() == 0);
        }
        check("Unadmitted lease has no frame", ChatWorkspacePreviewProtocol.select(
                catalog(binding(THREAD, 1).put("admitted", false)), THREAD).length() == 0);
        rejects("Catalog schema cannot silently change", () -> ChatWorkspacePreviewProtocol.select(
                catalog(own).put("schema", "unknown"), THREAD));
        rejects("Duplicate lease identity is ambiguous", () -> ChatWorkspacePreviewProtocol.select(catalog(own, own), THREAD));
        rejects("Screen key cannot choose another socket", () -> ChatWorkspacePreviewProtocol.select(
                catalog(binding(THREAD, 1).put("screenKey", "taku-agent-native")), THREAD));
        rejects("Malformed generation is rejected", () -> ChatWorkspacePreviewProtocol.select(
                catalog(binding(THREAD, 1).put("generation", "")), THREAD));
        rejects("Primary display is never previewed", () -> ChatWorkspacePreviewProtocol.select(
                catalog(binding(THREAD, 1).put("displayId", 0)), THREAD));
        for (int uid : new int[]{0, 1000, 2000}) {
            ChatWorkspacePreviewProtocol.validateHost(own, host(own, uid, 4321), uid, 4321);
            check("Platform host UID " + uid + " with matching identity", true);
        }
        rejects("App UID cannot host the stream", () -> ChatWorkspacePreviewProtocol.validateHost(
                own, host(own, 10001, 4321), 10001, 4321));
        rejects("Host must have a positive PID", () -> ChatWorkspacePreviewProtocol.validateHost(
                own, host(own, 0, 0), 0, 0));
        Object[][] changes = {
            {"pid", 4322}, {"uid", 1000}, {"displayId", 7}, {"screenKey", "task-22222222222222222222"},
            {"generation", OTHER}, {"mirrorSocket", "taku-mirror"},
            {"name", "Taku-Independent-Screen"}, {"stateDirectory", "/data/local/other"},
            {"width", 0}, {"height", 4097}
        };
        for (Object[] change : changes) {
            JSONObject changed = host(own, 0, 4321).put((String) change[0], change[1]);
            rejects("Reject changed host " + change[0],
                    () -> ChatWorkspacePreviewProtocol.validateHost(own, changed, 0, 4321));
        }
        check("Rebinding changes the UI frame identity", !ChatWorkspacePreviewProtocol.identity(own).equals(
                ChatWorkspacePreviewProtocol.identity(binding(THREAD, 1).put("generation", OTHER))));
        check("Display changes cannot reuse a pending frame", !ChatWorkspacePreviewProtocol.identity(own).equals(
                ChatWorkspacePreviewProtocol.identity(binding(THREAD, 1).put("displayId", 2))));
        homeSelectionChecks();
        verifiedCatalogChecks();
        System.out.println("PASS: " + checks + " workspace selection, lease and stream-host checks");
    }

    private static void homeSelectionChecks() throws Exception {
        JSONObject pausing = binding(THREAD, 1).put("status", "stopping").put("homePreviewEligible", true);
        JSONObject closing = binding(OTHER, 5).put("status", "stopping").put("homePreviewEligible", false);
        JSONObject bothThreads = catalog(binding(OTHER, 2).put("homePreviewEligible", true), pausing, closing,
                binding(THREAD, 3).put("status", "waiting_for_app").put("homePreviewEligible", true),
                binding(OTHER, 4).put("status", "user_control").put("homePreviewEligible", true));

        check("Empty Home catalog", ChatWorkspacePreviewProtocol.selectHome(catalog()).length() == 0);
        check("A single eligible screen is visible during pause",
                ChatWorkspacePreviewProtocol.selectHome(catalog(pausing, closing)).length() == 1);
        JSONArray home = ChatWorkspacePreviewProtocol.selectHome(bothThreads);
        check("Home lists eligible screens across threads in catalog order", home.length() == 4
                && home.getJSONObject(0).getInt("displayId") == 2
                && home.getJSONObject(1).getInt("displayId") == 1
                && home.getJSONObject(2).getInt("displayId") == 3
                && home.getJSONObject(3).getInt("displayId") == 4);
        check("Session selection still includes its own admitted screens",
                ChatWorkspacePreviewProtocol.select(bothThreads, OTHER).length() == 3);
        check("Home requires the eligibility flag",
                ChatWorkspacePreviewProtocol.selectHome(catalog(binding(THREAD, 1))).length() == 0);
        rejects("Home checks eligible screen identity", () -> ChatWorkspacePreviewProtocol.selectHome(
                catalog(binding("invalid", 1).put("homePreviewEligible", true))));
    }

    private static void verifiedCatalogChecks() throws Exception {
        JSONObject own = binding(THREAD, 1);
        String key = own.getString("screenKey");
        JSONObject original = catalog(own);
        ChatWorkspacePreviewProtocol.CatalogSnapshot snapshot =
                new ChatWorkspacePreviewProtocol.CatalogSnapshot(original, 100, 100);
        original.getJSONArray("screens").getJSONObject(0).put("admitted", false);
        check("Verified catalog owns an immutable copy", snapshot.select(THREAD, 100).length() == 1);
        snapshot.select(THREAD, 100).getJSONObject(0).put("admitted", false);
        check("A consumer cannot mutate the verified catalog", snapshot.select(THREAD, 100).length() == 1);
        check("Original five-second boundary is retained", snapshot.select(THREAD, 5100).length() == 1);
        rejects("Verified catalog expires without timestamp renewal", () -> snapshot.select(THREAD, 5101));
        rejects("A clock before verification is rejected", () -> snapshot.select(THREAD, 99));
        rejects("Zero verification time is rejected", () ->
                new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog(binding(THREAD, 1)), 0, 100));
        rejects("Future verification time is rejected", () ->
                new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog(binding(THREAD, 1)), 101, 100));
        rejects("Already-expired verification time is rejected", () ->
                new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog(binding(THREAD, 1)), 100, 5101));
        rejects("Exact preview cannot follow another thread", () -> snapshot.exact(OTHER, key, GENERATION, 100));
        rejects("Exact preview cannot follow another generation", () -> snapshot.exact(THREAD, key, OTHER, 100));
        rejects("Exact preview cannot follow another key", () -> snapshot.exact(THREAD,
                binding(THREAD, 2).getString("screenKey"), GENERATION, 100));

        ChatWorkspacePreviewProtocol.VerifiedCatalog shared = new ChatWorkspacePreviewProtocol.VerifiedCatalog();
        rejects("A preview cannot synthesize its own catalog", () -> shared.lease(THREAD, key, GENERATION, 100));
        long first = shared.beginRead();
        check("A real read publishes its verified snapshot", shared.publish(first, snapshot));
        ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease frame = shared.lease(THREAD, key, GENERATION, 100);
        for (int i = 0; i < 8; i++) {
            ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease next = shared.lease(THREAD, key, GENERATION, 100 + i);
            check("Multiple frame reads use the same owner response " + i,
                    shared.recheck(next, 101 + i).getInt("displayId") == 1);
        }
        rejects("A frame completing after source expiry is rejected", () -> shared.recheck(frame, 5101));
        long older = shared.beginRead(), newer = shared.beginRead();
        ChatWorkspacePreviewProtocol.CatalogSnapshot newerSnapshot =
                new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog(binding(THREAD, 1)), 5000, 5000);
        check("Newest response wins", shared.publish(newer, newerSnapshot));
        check("A late response cannot replace the winning catalog", !shared.publish(older, snapshot));
        shared.failedRead(older);
        check("A late read failure cannot clear the winning catalog",
                shared.lease(THREAD, key, GENERATION, 5001).binding.getInt("displayId") == 1);
        shared.failedLease(frame);
        check("An old frame failure cannot clear a newer snapshot",
                shared.lease(THREAD, key, GENERATION, 5001).binding.getInt("displayId") == 1);
        rejects("A newer catalog never renews an old frame's source timestamp", () -> shared.recheck(frame, 5101));

        ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease latest = shared.lease(THREAD, key, GENERATION, 5001);
        long inFlight = shared.beginRead();
        shared.failedLease(latest);
        rejects("A current frame failure clears its verified catalog", () -> shared.lease(THREAD, key, GENERATION, 5001));
        check("An in-flight real read may recover after frame failure", shared.publish(inFlight, newerSnapshot));
        rejects("A recovered catalog cannot revive a failed frame", () -> shared.recheck(latest, 5001));

        ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease beforeSelection = shared.lease(THREAD, key, GENERATION, 5001);
        long staleSelectionRead = shared.beginRead();
        shared.invalidate();
        check("Selection invalidation rejects an old read", !shared.publish(staleSelectionRead, newerSnapshot));
        rejects("Selection invalidation immediately removes the catalog", () -> shared.lease(THREAD, key, GENERATION, 5001));
        long selected = shared.beginRead();
        shared.publish(selected, newerSnapshot);
        rejects("Even the same lease after selection cannot revive an old frame", () -> shared.recheck(beforeSelection, 5001));
        ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease beforeFailure = shared.lease(THREAD, key, GENERATION, 5001);
        shared.failedRead(selected);
        rejects("The current catalog read failure clears previews", () -> shared.recheck(beforeFailure, 5001));

        long initial = shared.beginRead(); shared.publish(initial, newerSnapshot);
        ChatWorkspacePreviewProtocol.VerifiedCatalog.Lease beforeReplacement = shared.lease(THREAD, key, GENERATION, 5001);
        Object[][] replacements = {{"generation", OTHER}, {"displayId", 9}, {"threadId", OTHER}, {"admitted", false}};
        for (Object[] change : replacements) {
            JSONObject changed = binding(THREAD, 1).put((String) change[0], change[1]);
            long revision = shared.beginRead();
            shared.publish(revision, new ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog(changed), 5000, 5000));
            rejects("Frame return rejects replaced " + change[0], () -> shared.recheck(beforeReplacement, 5001));
        }
    }

    private static JSONObject binding(String thread, int index) throws Exception {
        return new JSONObject().put("threadId", thread).put("screenKey", String.format("task-%020x", index))
                .put("generation", GENERATION).put("displayId", index).put("packageName", "org.example.app" + index)
                .put("admitted", true).put("status", "running");
    }

    private static JSONObject catalog(JSONObject... screens) throws Exception {
        JSONArray rows = new JSONArray();
        for (JSONObject screen : screens) rows.put(screen);
        return new JSONObject().put("schema", "taku.workspaces/1").put("screens", rows);
    }

    private static JSONObject host(JSONObject binding, int uid, int pid) throws Exception {
        String key = binding.getString("screenKey");
        return new JSONObject().put("uid", uid).put("pid", pid).put("displayId", binding.getInt("displayId"))
                .put("screenKey", key).put("generation", binding.getString("generation"))
                .put("mirrorSocket", "taku-mirror-" + key).put("name", "Taku-Independent-Screen-" + key)
                .put("stateDirectory", "/data/local/agentos/virtual-display/screens/" + key)
                .put("width", 720).put("height", 1280);
    }

    private static void rejects(String name, Operation operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { check(name, true); return; }
        throw new AssertionError(name);
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
