package org.agentos.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Retired dashboard entry: HOME and all Panel navigation belong to Taku Agent. */
public final class CodexHomeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        forwardToHome();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        forwardToHome();
    }
    private void forwardToHome() {
        try { AgentFrontendLauncher.openHome(this); }
        catch (RuntimeException failure) { android.util.Log.w("TakuHome", "New home unavailable", failure); }
        finish();
    }
}
