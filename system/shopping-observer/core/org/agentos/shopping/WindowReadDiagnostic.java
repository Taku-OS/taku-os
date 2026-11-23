// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.List;
import java.util.Set;

/** Fixed metadata only: no arbitrary package, title, node text, or exception message. */
final class WindowReadDiagnostic {
    static final int MAX_WINDOWS=16;
    enum PackageKind { TIKTOK, SHOPPING, SURFACES, SYSTEM_UI, OTHER, UNKNOWN }
    record Window(int id,int type,int layer,boolean active,boolean focused,boolean accessibilityFocused,
                  boolean pip,int left,int top,int right,int bottom,PackageKind packageKind) {
        Window {
            id=bounded(id,-1,1_000_000);type=bounded(type,-1,100);layer=bounded(layer,-1,1_000_000);
            left=coordinate(left);top=coordinate(top);right=coordinate(right);bottom=coordinate(bottom);
            if(packageKind==null)packageKind=PackageKind.UNKNOWN;
        }
    }
    private static final Set<String> REASONS=Set.of("none","wrong_thread","screen_locked","window_unavailable",
        "unsupported_orientation","keyboard_visible","picture_in_picture","multiple_app_windows",
        "app_not_focused","window_not_fullscreen","root_unavailable","outside_tiktok","not_live_room",
        "live_room","try_on_preview","snapshot_unavailable","unknown");
    private WindowReadDiagnostic(){}
    static PackageKind packageKind(CharSequence value) {
        if(value==null||value.length()==0)return PackageKind.UNKNOWN;
        String pkg=value.toString();
        if(SnapshotParser.TIKTOK_PACKAGES.contains(pkg))return PackageKind.TIKTOK;
        return switch(pkg) {
            case "org.agentos.shopping" -> PackageKind.SHOPPING;
            case "org.agentos.surfaces" -> PackageKind.SURFACES;
            case "com.android.systemui" -> PackageKind.SYSTEM_UI;
            default -> PackageKind.OTHER;
        };
    }
    static String render(String reason,List<Window> windows,int total) {
        int count=windows==null?0:Math.min(MAX_WINDOWS,windows.size());
        String safeReason=reason!=null&&REASONS.contains(reason)?reason:"unknown";
        StringBuilder out=new StringBuilder(512);
        out.append("{\"schema\":\"taku.window-read.v1\",\"reason\":\"")
            .append(safeReason)
            .append("\",\"defaultWindowCount\":").append(bounded(total,0,1_000_000))
            .append(",\"truncated\":").append(total>count).append(",\"windows\":[");
        boolean first=true;
        for(int i=0;i<count;i++) {
            Window w=windows.get(i);if(w==null)continue;
            if(!first)out.append(',');first=false;
            out.append("{\"id\":").append(w.id()).append(",\"type\":").append(w.type())
                .append(",\"layer\":").append(w.layer()).append(",\"active\":").append(w.active())
                .append(",\"focused\":").append(w.focused()).append(",\"accessibilityFocused\":").append(w.accessibilityFocused())
                .append(",\"pip\":").append(w.pip()).append(",\"bounds\":[").append(w.left()).append(',')
                .append(w.top()).append(',').append(w.right()).append(',').append(w.bottom())
                .append("],\"package\":\"").append(w.packageKind().name()).append("\"}");
        }
        return out.append("]}").toString();
    }
    private static int coordinate(int value){return bounded(value,-16384,16384);}
    private static int bounded(int value,int minimum,int maximum){return Math.max(minimum,Math.min(maximum,value));}
}
