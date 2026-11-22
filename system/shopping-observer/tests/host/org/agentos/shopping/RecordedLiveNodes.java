// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.ArrayList;
import java.util.List;

/** Minimal, public UI fragments from the two Pixel 9 / TikTok 41.3.3 captures on 2026-11-02. */
final class RecordedLiveNodes {
    static final String PACKAGE = "com.zhiliaoapp.musically";
    static final String ACTIVITY = "com.ss.android.ugc.aweme.live.LivePlayActivity";
    static final String CAPE = "Autumn Winter Retro Plaid Hooded Cape, Dual-Sided Design Shawl, Warm Outerwear for Daily Commute, Elegant and Luxurious Style";
    static SnapshotParser.Node node(String id, String text, String desc, int left, int top, int right, int bottom) {
        return new SnapshotParser.Node(PACKAGE, "android.widget.TextView", text, desc,
            id.isEmpty() ? "" : PACKAGE + ":id/" + id, left, top, right, bottom, true);
    }
    static List<SnapshotParser.Node> room(String host, boolean product) {
        List<SnapshotParser.Node> nodes = new ArrayList<>();
        nodes.add(node("ahd", "", host + (product ? ",117.7K" : ",350"), 32,194,product ? 679 : 639,289));
        nodes.add(node("wei", host, "", 137,200,product ? 461 : 421,245));
        nodes.add(node("mr1", "", product ? "264 次觀看" : "9 次觀看", 864,210,964,273));
        nodes.add(node("vey", product ? "264" : "9", "", product ? 885 : 904,223,product ? 942 : 923,261));
        nodes.add(node("d1i", "", "關閉", 996,210,1059,273));
        nodes.add(node("", "禮物庫", "禮物庫", 502,305,754,368));
        nodes.add(node("f7z", "輸入...", "", 148,2234,489,2329));
        nodes.add(node("", product ? "$2 折扣" : "$8 折扣", product ? "$2 折扣" : "$8 折扣", 32,384,152,440));
        if (product) {
            nodes.add(node("f62", "", "product image", 48,1992,253,2197));
            nodes.add(node("f5p", CAPE, "", 274,1992,959,2039));
            nodes.add(node("f63", "$36.49", "", 274,2147,399,2197));
            nodes.add(node("f5k", "$87.99", "", 404,2154,491,2191));
            nodes.add(node("f4t", "-59%", "", 496,2154,576,2191));
            nodes.add(node("f4m", "購買", "", 859,2134,1027,2197));
        }
        return nodes;
    }
    static List<SnapshotParser.Node> highland() { return room("Highland Fashion", true); }
    static List<SnapshotParser.Node> cindy() { return room("Cindy Clothing", false); }
    static LivePage parse(List<SnapshotParser.Node> nodes) {
        return SnapshotParser.parse(PACKAGE, ACTIVITY, 100, 0, 1080, 2424, nodes);
    }
    static LivePage page(String host, String product, int window) { return LivePage.of(PACKAGE, window, host, product); }
}
