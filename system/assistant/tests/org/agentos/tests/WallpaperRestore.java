package org.agentos.tests;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.FileInputStream;
import org.json.JSONObject;

/** Test cleanup only. The agent must change wallpaper using tools already on the device. */
public final class WallpaperRestore {
    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        WallpaperManager manager = WallpaperManager.getInstance(context);
        if (args.length > 0 && args[0].equals("clear-lock")) manager.clear(WallpaperManager.FLAG_LOCK);
        if (args.length > 0 && args[0].equals("restore")) {
            if (args.length > 1) try (FileInputStream original = new FileInputStream(args[1])) {
                manager.setStream(original, null, true, WallpaperManager.FLAG_SYSTEM);
            }
            else manager.clear(WallpaperManager.FLAG_SYSTEM);
        }
        JSONObject snapshot = new JSONObject().put("systemId", manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                .put("lockId", manager.getWallpaperId(WallpaperManager.FLAG_LOCK))
                .put("liveWallpaper", manager.getWallpaperInfo() != null);
        // Read the installed image only in the root verifier, never export wallpaper bytes.
        if (android.os.Process.myUid() == 0) {
            Bitmap image = BitmapFactory.decodeFile("/data/system/users/0/wallpaper");
            if (image != null) {
                snapshot.put("systemCenterRgb", image.getPixel(image.getWidth()/2, image.getHeight()/2) & 0xffffff);
                image.recycle();
            }
        }
        System.out.println(snapshot);
        System.exit(0);
    }
}
