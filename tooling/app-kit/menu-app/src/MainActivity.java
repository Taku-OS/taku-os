package org.agentos.generated.menu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String TAG = "GeneratedMenu";
    private static final String PAGE = "file:///android_asset/index.html";
    private WebView webView;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getEncodedPath();
                if ("file".equals(url.getScheme()) && TextUtils.isEmpty(url.getAuthority()) && path != null
                        && path.matches("/android_asset/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")
                        && (!request.isForMainFrame() || PAGE.equals(url.toString()))) {
                    return null;
                }
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        webView.addJavascriptInterface(new MenuBridge(), "MenuApp");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xffd6dadd);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        root.addView(webView);
        setContentView(root);
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(lightBars, lightBars);
        webView.loadUrl(PAGE);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("MenuApp");
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
        }
        super.onDestroy();
    }

    private Intent postIntent(String url, String targetPackage) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(targetPackage)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER);
    }

    private boolean canOpen(Intent intent) {
        return getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    public final class MenuBridge {
        private Map<String, String> openablePosts = Map.of();

        @JavascriptInterface
        public synchronized String readMenu() {
            try (InputStream input = getAssets().open("menu-data.json")) {
                JSONObject menu = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                JSONArray dishes = menu.getJSONArray("dishes");
                Map<String, String> routes = new HashMap<>();
                for (int index = 0; index < dishes.length(); index++) {
                    JSONObject dish = dishes.getJSONObject(index);
                    JSONArray recommendations = dish.getJSONArray("recommendations");
                    JSONArray openable = new JSONArray();
                    for (int recommendation = 0; recommendation < recommendations.length(); recommendation++) {
                        JSONObject post = recommendations.getJSONObject(recommendation);
                        String url = post.getString("url");
                        String target = RecommendationLinks.nativePackage(post.getString("platform"), url);
                        if (target != null && canOpen(postIntent(url, target))) {
                            openable.put(post);
                            routes.put(url, target);
                        }
                    }
                    dish.put("recommendations", openable);
                }
                openablePosts = routes;
                return menu.toString();
            } catch (IOException | JSONException error) {
                Log.e(TAG, "Unable to read the packaged menu", error);
                openablePosts = Map.of();
                return "";
            }
        }

        @JavascriptInterface
        public synchronized boolean openRecommendation(String url) {
            if (url == null) return false;
            String target = openablePosts.get(url);
            if (target == null) return false;
            Intent intent = postIntent(url, target);
            if (!canOpen(intent)) return false;
            runOnUiThread(() -> {
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(MainActivity.this, "The original post could not be opened.",
                            Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        }
    }
}
