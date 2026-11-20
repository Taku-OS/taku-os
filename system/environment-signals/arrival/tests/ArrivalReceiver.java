package org.agentos.arrivalfixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class ArrivalReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Fixture.ACTION.equals(intent.getAction())) return;
        try {
            Fixture.onArrivalAvailable(context);
        } catch (Exception e) {
            Log.e("ArrivalFixture", "Arrival broadcast failed", e);
        }
    }
}
