// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Conservative adapter for the native live-room nodes verified on TikTok 41.3.3. */
public final class SnapshotParser {
    public static final Set<String> TIKTOK_PACKAGES = Set.of("com.zhiliaoapp.musically", "com.ss.android.ugc.trill");
    public record Node(String packageName, String className, String text, String description,
                       String viewId, int left, int top, int right, int bottom, boolean visible) {
        public Node {
            packageName = value(packageName); className = value(className); text = value(text);
            description = value(description); viewId = value(viewId);
        }
        private static String value(String value) { return value == null ? "" : value; }
    }
    private SnapshotParser() {}

    public static LivePage parse(String foregroundPackage, String activityName, int windowId,
                                 int displayId, int width, int height, List<Node> nodes) {
        if (!TIKTOK_PACKAGES.contains(foregroundPackage) || windowId < 0 || displayId != 0
                || width <= 0 || height <= width || nodes == null || nodes.size() > 2048) return null;
        List<Node> visible = new ArrayList<>();
        for (Node node : nodes) if (node != null && node.visible()
                && foregroundPackage.equals(node.packageName()) && valid(node, width, height)) visible.add(node);
        Node host = unique(visible, foregroundPackage, "wei", true, height);
        Node header = unique(visible, foregroundPackage, "ahd", true, height);
        Node close = unique(visible, foregroundPackage, "d1i", true, height);
        Node viewers = unique(visible, foregroundPackage, "mr1", true, height);
        if (viewers == null) viewers = unique(visible, foregroundPackage, "vey", true, height);
        Node composer = unique(visible, foregroundPackage, "f7z", false, height);
        if (host == null || header == null || close == null || viewers == null || composer == null) return null;
        String name = LivePage.clean(host.text());
        if (name.isEmpty() || name.length() > 160 || close.left() < width * .70
                || host.left() > width * .50 || composer.top() < height * .75) return null;
        String headerLabel = LivePage.canonical(header.description() + " " + header.text());
        if (!headerLabel.contains(LivePage.canonical(name))) return null;
        boolean liveActivity = activityName != null && activityName.endsWith(".LivePlayActivity");
        boolean giftRegion = visible.stream().anyMatch(node -> node.top() < height * .25
            && isGiftLabel(node.text(), node.description()));
        // A search tab saying LIVE, an arbitrary creator label, or an activity name alone is insufficient.
        if (!liveActivity && !giftRegion) return null;

        Node title = unique(visible, foregroundPackage, "f5p", false, height);
        Node image = unique(visible, foregroundPackage, "f62", false, height);
        Node buy = unique(visible, foregroundPackage, "f4m", false, height);
        Node price = unique(visible, foregroundPackage, "f63", false, height);
        Node availability = unique(visible, foregroundPackage, "f64", false, height);
        boolean hasAvailability = availability != null && !LivePage.clean(availability.text()).isEmpty();
        String product = "";
        if (title != null && image != null && buy != null && (price != null || hasAvailability)
                && title.top() > height * .55 && image.top() > height * .55
                && buy.top() > height * .55 && Math.abs(title.top() - image.top()) < height * .18) {
            product = LivePage.clean(title.text());
            if (product.length() > 1024) product = "";
        }
        return LivePage.of(foregroundPackage, windowId, name, product);
    }

    private static boolean isGiftLabel(String text, String description) {
        String label = LivePage.canonical(text.isBlank() ? description : text);
        return Set.of("禮物庫", "礼物库", "gift gallery", "gift vault", "gift library").contains(label);
    }

    private static boolean valid(Node node, int width, int height) {
        return node.right() > node.left() && node.bottom() > node.top() && node.left() < width
            && node.right() > 0 && node.top() < height && node.bottom() > 0;
    }

    private static Node unique(List<Node> nodes, String pkg, String suffix, boolean top, int height) {
        Node result = null;
        String id = pkg + ":id/" + suffix;
        for (Node node : nodes) if (id.equals(node.viewId())
                && (top ? node.top() < height * .22 : node.top() >= height * .50)) {
            if (result != null) return null;
            result = node;
        }
        return result;
    }
}
