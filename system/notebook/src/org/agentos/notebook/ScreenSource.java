package org.agentos.notebook;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

record ScreenSource(String packageName, String label, String url, int windowId, List<String> webText,
        String postText) {
    static final ScreenSource UNKNOWN = new ScreenSource("", "Unknown source", null, -1, List.of(), "");
    private static final Pattern X_HANDLE = Pattern.compile("@[A-Za-z0-9_]{1,15}");
    private static final Map<String, List<String>> ADDRESS_BARS = Map.of(
            "com.android.chrome", List.of("url_bar"),
            "org.chromium.chrome", List.of("url_bar"),
            "org.lineageos.jelly", List.of("autoCompleteTextView"));

    static ScreenSource read(AccessibilityService service, boolean includeUrl) {
        return read(service, includeUrl, false);
    }

    static ScreenSource read(AccessibilityService service, boolean includeUrl, boolean includePostText) {
        var windows = service.getWindowsOnAllDisplays().get(Display.DEFAULT_DISPLAY);
        if (windows == null) return UNKNOWN;
        for (var window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                    || (!window.isActive() && !window.isFocused() && windows.stream().anyMatch(candidate ->
                            candidate.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && candidate.isFocused()))) continue;
            var root = window.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String pkg = root.getPackageName().toString();
            if (pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui")) continue;
            String page = includeUrl ? url(root, pkg) : null;
            String source = label(service.getPackageManager(), pkg);
            if (page != null) {
                String host = Uri.parse(page).getHost();
                if ("x.com".equalsIgnoreCase(host) || "twitter.com".equalsIgnoreCase(host)) source = "X";
            }
            return new ScreenSource(pkg, source, page, window.getId(),
                    page == null ? List.of() : visibleWebText(root),
                    includePostText && "com.twitter.android".equals(pkg) ? visibleXPostText(root) : "");
        }
        return UNKNOWN;
    }

    private static List<String> visibleWebText(AccessibilityNodeInfo root) {
        var stack = new ArrayDeque<AccessibilityNodeInfo>();
        var text = new ArrayList<String>();
        stack.push(root);
        for (int visited = 0; !stack.isEmpty() && visited < 80 && text.size() < 20; visited++) {
            var node = stack.pop();
            if (!node.isVisibleToUser()) continue;
            if (!node.isEditable() && !node.isPassword()) {
                var value = node.getText() == null ? node.getContentDescription() : node.getText();
                if (value != null && !value.toString().isBlank() && value.length() <= 100) {
                    text.add(value.toString());
                }
            }
            for (int i = Math.min(node.getChildCount(), 18) - 1; i >= 0; i--) {
                var child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return List.copyOf(text);
    }

    private static String visibleXPostText(AccessibilityNodeInfo root) {
        var stack = new ArrayDeque<AccessibilityNodeInfo>();
        var posts = new ArrayList<String>();
        stack.push(root);
        for (int visited = 0; !stack.isEmpty() && visited < 240; visited++) {
            var node = stack.pop();
            if (!node.isVisibleToUser()) continue;
            if (node.isClickable() && node.getClassName() != null
                    && "android.view.View".contentEquals(node.getClassName())) {
                String post = xPostText(node);
                if (!post.isEmpty()) posts.add(post);
            }
            if (node.getChildCount() > 24) return "";
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                var child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        return stack.isEmpty() && posts.size() == 1 ? posts.get(0) : "";
    }

    private static String xPostText(AccessibilityNodeInfo node) {
        if (node.getChildCount() > 24) return "";
        boolean hasHandle = false;
        String body = "";
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child == null || !child.isVisibleToUser() || child.isPassword() || child.isEditable()
                    || child.getClassName() == null
                    || !"android.widget.TextView".contentEquals(child.getClassName()) || child.getText() == null) {
                continue;
            }
            Rect bounds = new Rect();
            child.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) continue;
            String text = child.getText().toString().replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
            if (X_HANDLE.matcher(text).matches()) {
                hasHandle = true;
            } else if (text.length() >= 25 && text.length() <= 280) {
                if (!body.isEmpty()) return "";
                body = text;
            }
        }
        if (!hasHandle || body.isEmpty()) return "";
        int length = body.codePointCount(0, body.length());
        return length <= 100 ? body : body.substring(0, body.offsetByCodePoints(0, 100));
    }

    boolean matches(ScreenSource other) {
        return windowId == other.windowId && packageName.equals(other.packageName);
    }

    ScreenSource withPostText(String text) {
        return new ScreenSource(packageName, label, url, windowId, webText, text);
    }

    private static String label(PackageManager packages, String pkg) {
        try {
            return packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException unavailable) {
            return pkg;
        }
    }

    private static String url(AccessibilityNodeInfo root, String pkg) {
        for (String id : ADDRESS_BARS.getOrDefault(pkg, List.of())) {
            for (var address : root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id)) {
                if (!address.isVisibleToUser() || address.isFocused() || address.getText() == null) continue;
                String value = browserAddress(address.getText().toString());
                if (value != null) return value;
            }
        }
        return null;
    }
    // Chrome omits the scheme in a trusted, non-focused address bar on newer Android builds.
    static String browserAddress(String value) {
        String text = value.strip();
        if (text.isEmpty() || text.indexOf('\u2026') >= 0 || text.codePoints().anyMatch(Character::isWhitespace)) return null;
        if (!text.contains("://")) text = "https://" + text;
        return NotebookStore.verifiedWebUrl(text);
    }

}
