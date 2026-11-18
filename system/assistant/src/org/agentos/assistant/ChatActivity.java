package org.agentos.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Private navigation alias. All visible chat UI belongs to the system assistant session. */
public final class ChatActivity extends Activity {
    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent request = new Intent(this, PanelActivity.class);
        if (getIntent().getExtras() != null) request.putExtras(getIntent().getExtras());
        startActivity(request);
        finish();
    }
}
