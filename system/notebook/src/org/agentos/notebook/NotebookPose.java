package org.agentos.notebook;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;
import java.util.List;

final class NotebookPose {
    private static final String CAMERA_POSE_ACTION = "org.taku.camera.action.USE_NOTEBOOK_POSE";
    private static final String CAMERA_MAIN_ACTIVITY = "org.taku.camera.preview.MainActivity";
    private static final List<String> CAMERA_PACKAGES = List.of("org.taku.camera.styledemo", "org.taku.camera.preview");

    private NotebookPose() {}

    static boolean isAvailable(NotebookStore store, NotebookStore.Entry entry, NotebookArrivalIntent.Hint hint) {
        if (hint == null || !hint.poseEligible()) return false;
        try {
            store.getPoseSource(entry.id);
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    static void open(Context context, String id) throws IOException {
        NotebookStore.Entry entry = new NotebookStore(context).getPoseSource(id);
        NotebookArrivalIntent.Hint hint = NotebookArrivalIntent.cached(context, entry);
        if (hint == null) throw new IOException("POSE_ELIGIBILITY_UNAVAILABLE");
        if (!hint.poseEligible()) throw new IOException("POSE_NOT_ELIGIBLE");
        Intent intent = new Intent(CAMERA_POSE_ACTION, entry.imageUri)
                .putExtra("notebookImageSha256", entry.sha256)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Notebook reference", entry.imageUri));
        for (String cameraPackage : CAMERA_PACKAGES) {
            try {
                context.startActivity(intent.setClassName(cameraPackage, CAMERA_MAIN_ACTIVITY));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Some builds include only one of the Camera variants.
            }
        }
        throw new IOException("CAMERA_OPEN_UNAVAILABLE");
    }
}
