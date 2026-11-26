package org.agentos.notebook;

import android.app.Activity;

public final class NotebookScreenshotActionActivity extends Activity {
    private boolean dispatched;

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || dispatched || isFinishing()) return;
        dispatched = true;
        NotebookScreenshotService.onIslandAction(this, getIntent().getIdentifier(), getIntent().getAction());
    }
}
