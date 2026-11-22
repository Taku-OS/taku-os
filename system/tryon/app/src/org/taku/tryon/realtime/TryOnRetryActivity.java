// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.Intent;
/** Non-exported target reached only by app-owned Retry and notification controls. */
public final class TryOnRetryActivity extends TryOnActivity {
    @Override protected boolean allowResultIntent(Intent intent){
        return intent==null||!intent.hasExtra("resultToken")||TryOnResultWindow.claimRetry(intent.getStringExtra("resultToken"));
    }
}
