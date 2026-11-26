package org.agentos.lab;

import java.util.List;
import org.json.JSONObject;
import static org.agentos.lab.DedicatedScreensTest.*;

/** Exercise actual lease lifecycle with synthetic apps and a hand-moved clock. */
public final class PresentationRetentionTest {
    private static JSONObject row(Rig rig) throws Exception { return rig.screens.list(session(1)).getJSONObject(0); }
    private static JSONObject present(Rig rig, int seconds) throws Exception {
        JSONObject got = row(rig);
        return rig.screens.present(session(1), got.getString("screenKey"), got.getString("generation"), seconds);
    }
    private static void ended(Rig rig, boolean successful) throws Exception {
        Thread release = rig.screens.turnEnded(session(1), successful);
        if (release != null) { release.join(5000); check(!release.isAlive(), "cleanup finishes"); }
    }
    private static Rig started() throws Exception {
        Rig rig = new Rig();
        JSONObject got = rig.request(1, "preview", "com.example.preview");
        rig.tasks.open("com.example.preview", got.getInt("displayId"));
        return rig;
    }
    private static void expireAfterCompletion() throws Exception {
        Rig rig = started();
        present(rig, 10); ended(rig, true);
        check(row(rig).getBoolean("presentationReady") && row(rig).getString("status").equals("ready"), "successful opt-in becomes ready");
        check(!rig.tasks.tasks.isEmpty(), "completed result remains visible");
        rig.clock.advance(10000);
        DedicatedScreensFamilyTest.until(() -> rig.tasks.tasks.isEmpty(), "TTL releases completed result");
        check(rig.screens.list(session(1)).isEmpty(), "expired screen no longer belongs to the thread");
    }
    private static void renewalAndRunningTurn() throws Exception {
        Rig rig = started(); present(rig, 10); ended(rig, true);
        rig.clock.advance(5000);
        check(rig.screens.turnStarted(session(1), rig.screens.admission(session(1))), "same lease admits the next turn");
        check(!row(rig).getBoolean("presentationReady"), "new turn clears ready marker");
        present(rig, 10); rig.clock.advance(5000);
        check(row(rig).getLong("presentationRemainingMs") == 5000, "obsolete timer cannot cancel renewed presentation");
        rig.clock.advance(5000);
        check(!rig.tasks.tasks.isEmpty() && row(rig).getLong("presentationRemainingMs") == 0, "expiry does not stop an active turn");
        ended(rig, true);
        check(rig.tasks.tasks.isEmpty(), "expired hold cannot survive completion");
    }
    private static void failureAndExplicitCleanup() throws Exception {
        Rig failed = started(); present(failed, 10); ended(failed, false);
        check(failed.tasks.tasks.isEmpty(), "failed or interrupted turns release despite a hold");
        Rig ordinary = started(); ended(ordinary, true);
        check(ordinary.tasks.tasks.isEmpty(), "success without show remains ordinary cleanup");
        Rig removed = started(); present(removed, 10); ended(removed, true);
        Thread gone = removed.screens.sessionGone(session(1)); gone.join(5000);
        check(removed.tasks.tasks.isEmpty(), "session deletion overrides presentation");
        Rig empty = started(); present(empty, 10);
        empty.screens.release(session(1), List.of("com.example.preview"));
        check(row(empty).getLong("presentationRemainingMs") == 0, "release of all apps clears the hold");
        ended(empty, true);
        check(empty.screens.list(session(1)).isEmpty(), "empty presentation cannot retain a lease");
    }
    private static void exactLeaseAndUserControl() throws Exception {
        Rig rig = started(); JSONObject got = row(rig);
        rejects("SCREEN", () -> rig.screens.present(session(1), got.getString("screenKey"), "stale-generation", 10));
        rejects("INVALID_PRESENTATION_DURATION", () -> present(rig, 1801));
        present(rig, 10); ended(rig, true);
        rig.screens.beginPause(session(1), got.getString("screenKey"), got.getString("generation"));
        rig.screens.finishControl(session(1), got.getString("screenKey"), got.getString("generation"), "user");
        rig.clock.advance(10000);
        check(!rig.tasks.tasks.isEmpty() && row(rig).getString("controlOwner").equals("user"), "expiry preserves a user-owned screen");
        rejects("PAUSED", () -> present(rig, 10));
        Thread gone = rig.screens.sessionGone(session(1)); gone.join(5000);
        check(rig.tasks.tasks.isEmpty(), "explicit deletion still releases the user-owned screen");
    }
    public static void main(String[] args) throws Exception {
        expireAfterCompletion(); renewalAndRunningTurn(); failureAndExplicitCleanup(); exactLeaseAndUserControl();
        System.out.println("PresentationRetentionTest: " + checks + " checks passed (fake clock/tasks/host)");
    }
}
