package org.agentos.notebook;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;
import java.io.IOException;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotebookArrivalActionActivity extends Activity {
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || isFinishing()) return;
        if (!getSystemService(KeyguardManager.class).isDeviceLocked()) openSelection();
        finish();
    }

    private void openSelection() {
        Intent intent = getIntent();
        Uri event = intent.getData();
        String entryId = intent.getStringExtra("entryId");
        String action = intent.getAction();
        if (event == null || !NotebookArrivalPresentation.ARRIVAL_SCHEME.equals(event.getScheme())
                || !NotebookStore.validId(entryId) || action == null
                || SystemClock.elapsedRealtime() >= intent.getLongExtra("expiresAt", 0)) {
            return;
        }

        boolean opened = switch (action) {
            case NotebookArrivalPresentation.OPEN_POST -> requestOpen("openSource", entryId);
            case NotebookArrivalPresentation.OPEN_SAVED_ITEM -> requestOpen("open", entryId);
            case NotebookArrivalPresentation.OPEN_DIRECTIONS -> openDirections(entryId, intent.getStringExtra("placeId"));
            default -> false;
        };
        if (opened) {
            getSystemService(NotificationManager.class).cancel(
                    event.getSchemeSpecificPart(), NotebookArrivalPresentation.NOTIFICATION_ID);
            return;
        }

        boolean chinese = NotebookArrivalPresentation.isChinese(this);
        String message = switch (action) {
            case NotebookArrivalPresentation.OPEN_POST -> chinese ? "暂时无法打开原帖" : "Couldn't open the original post";
            case NotebookArrivalPresentation.OPEN_DIRECTIONS -> chinese ? "暂时无法打开步行路线" : "Couldn't open walking directions";
            default -> chinese ? "暂时无法打开收藏" : "Couldn't open the saved item";
        };
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean openDirections(String entryId, String placeId) {
        NotebookPlaces.Place place;
        try {
            place = NotebookArrivals.placeForEntry(this, entryId);
        } catch (IOException | JSONException unavailable) {
            return false;
        }
        if (place == null || !place.id().equals(placeId)
                || !Double.isFinite(place.latitude()) || Math.abs(place.latitude()) > 90
                || !Double.isFinite(place.longitude()) || Math.abs(place.longitude()) > 180) {
            return false;
        }
        Uri directions = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", place.latitude() + "," + place.longitude())
                .appendQueryParameter("travelmode", "walking")
                .appendQueryParameter("dir_action", "navigate")
                .build();
        Intent view = new Intent(Intent.ACTION_VIEW, directions);
        try {
            startActivity(new Intent(view).setPackage("com.google.android.apps.maps"));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                startActivity(view);
            } catch (ActivityNotFoundException | SecurityException failed) {
                return false;
            }
        }
        return true;
    }

    private boolean requestOpen(String method, String entryId) {
        String argument = new JSONObject(Map.of("id", entryId)).toString();
        try {
            Bundle reply = getContentResolver().call(NotebookStore.URI, method, argument, null);
            if (reply == null) return false;
            return new JSONObject(reply.getString("result", "{}")).optBoolean("ok");
        } catch (JSONException | RuntimeException unavailable) {
            return false;
        }
    }
}
