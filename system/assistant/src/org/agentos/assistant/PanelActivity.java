package org.agentos.assistant;

import android.app.Activity;
import android.os.Bundle;

/** Compatibility for existing notifications/links; never changes the selected voice service. */
public final class PanelActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Bundle args = getIntent().getExtras() == null ? new Bundle() : new Bundle(getIntent().getExtras());
        if (!AgentFrontendLauncher.openConversation(this, args))
            NativeControlsActivity.open(this, args, null);
        finish();
    }
}
