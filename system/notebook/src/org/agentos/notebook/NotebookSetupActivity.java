package org.agentos.notebook;

import android.app.Activity;
import android.os.Bundle;

public final class NotebookSetupActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(NotebookLibraryActivity.settingsIntent(this));
        finish();
    }
}
