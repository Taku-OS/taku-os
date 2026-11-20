package org.agentos.environment.arrival;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

public final class ArrivalReceiver extends BroadcastReceiver {
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        WORKER.execute(() -> {
            try {
                Location fix = ArrivalRuntime.LOCATION_UPDATE.equals(intent.getAction())
                        ? intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location.class) : null;
                ArrivalRuntime.get(context).onWake(intent.getAction(), fix);
            } catch (RuntimeException failure) {
                Log.w("TakuArrival", "Unable to process arrival wakeup", failure);
            } finally {
                result.finish();
            }
        });
    }
}
