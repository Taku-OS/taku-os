package org.agentos.notebook;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.util.Set;
import org.agentos.ai.NotebookSocialSource;

final class NotebookSourceLink {
    private static final String X_APP = "com.twitter.android";
    private static final Set<String> BROWSERS = Set.of(
            "org.lineageos.jelly", "com.android.chrome", "org.chromium.chrome");
    private NotebookSourceLink() {}

    static Uri resolve(String value) {
        String url = NotebookStore.verifiedWebUrl(value);
        return url == null ? null : Uri.parse(url);
    }

    static boolean open(Context context, Uri source, String sourcePackage) {
        Intent intent = new Intent(Intent.ACTION_VIEW, source).addCategory(Intent.CATEGORY_BROWSABLE);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (NotebookStore.isXPostUrl(source.toString())) {
            return launch(context, intent.setPackage(X_APP));
        }
        NotebookSocialSource platform = NotebookSocialSource.byPackage(sourcePackage);
        String itemUrl = platform == null ? null : platform.canonicalUrl(source.toString());
        if (platform != NotebookSocialSource.X && itemUrl != null) {
            if (platform == NotebookSocialSource.REDNOTE) {
                String noteId = Uri.parse(itemUrl).getLastPathSegment();
                Uri note = new Uri.Builder().scheme("xhsdiscover").authority("item").appendPath(noteId).build();
                if (launch(context, new Intent(intent).setPackage(sourcePackage).setData(note))) return true;
            }
            String activity = platform.nativeViewActivity(sourcePackage);
            if (activity != null && launch(context, new Intent(intent).setClassName(sourcePackage, activity))) return true;
            if (launch(context, new Intent(intent).setPackage(sourcePackage))) return true;
            return openBrowser(context, intent);
        }
        if (sourcePackage != null && BROWSERS.contains(sourcePackage)
                && launch(context, new Intent(intent).setPackage(sourcePackage))) return true;
        return launch(context, intent);
    }

    private static boolean openBrowser(Context context, Intent webIntent) {
        Intent browsers = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER);
        for (var browser : context.getPackageManager()
                .queryIntentActivities(browsers, PackageManager.ResolveInfoFlags.of(0))) {
            if (launch(context, new Intent(webIntent).setPackage(browser.activityInfo.packageName))) return true;
        }
        return launch(context, webIntent);
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
