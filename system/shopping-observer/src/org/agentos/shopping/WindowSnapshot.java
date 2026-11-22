// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads only the verified room/card nodes; chat messages and editable text never enter the snapshot. */
public final class WindowSnapshot {
    /** Context metadata never substitutes for a verified page and contains no node text. */
    public record ReadResult(LivePage page, String reason, int windowId, String packageName, LiveFrameWindow.Layout layout,
            OfferOcclusionGuard.Evidence offerOcclusion) {
        public ReadResult(LivePage page,String reason,int windowId,String packageName,LiveFrameWindow.Layout layout) {
            this(page,reason,windowId,packageName,layout,null);
        }
        public ReadResult(LivePage page,String reason,int windowId,String packageName) {
            this(page,reason,windowId,packageName,null);
        }
        public ReadResult(LivePage page, String reason) {
            this(page,reason,page==null?-1:page.windowId(),page==null?"":page.packageName());
        }
    }
    private static volatile String lastDiagnostic=WindowReadDiagnostic.render("none",List.of(),0);
    /** A completed read's fixed snapshot; never performs another window read. */
    public static String diagnostic(){return lastDiagnostic;}
    private static final String[] IDS = {"wei", "ahd", "d1i", "mr1", "vey", "f7z", "f5p", "f62", "f4m", "f63", "f64"};
    private static final String[] GIFT_QUERIES = {"禮物庫", "礼物库", "Gift"};
    private static final int MAX_GIFT_NODES = 250;
    private static final int MAX_PREVIEW_NODES = 256, MAX_PREVIEW_DEPTH = 12, MAX_PREVIEW_CHILDREN = 32;
    private WindowSnapshot() {}

    public static ReadResult read(AccessibilityService service, String activityName) {
        return read(service,activityName,false);
    }

    /** Capture boundaries request layout from this exact verified root, never a prior read's geometry. */
    static ReadResult read(AccessibilityService service, String activityName,boolean withLayout) {
        return read(service,activityName,withLayout,null);
    }

    /** Display maintenance only. Hidden identity anchors never become a page or capture layout. */
    static ReadResult readForOffer(AccessibilityService service,String activityName,LivePage source) {
        return read(service,activityName,false,source);
    }

    /** Passive observation must not replace an attached preview with another outfit suggestion. */
    static ReadResult readPassive(AccessibilityService service,String activityName,LivePage source) {
        return read(service,activityName,false,source,true);
    }

    private static ReadResult read(AccessibilityService service,String activityName,boolean withLayout,
            LivePage offerSource) {
        return read(service,activityName,withLayout,offerSource,false);
    }

    private static ReadResult read(AccessibilityService service,String activityName,boolean withLayout,
            LivePage offerSource,boolean passive) {
        if (Looper.myLooper() != Looper.getMainLooper()) return finish(absent("wrong_thread"),null,null);
        List<AccessibilityWindowInfo> windows = null;
        AccessibilityNodeInfo root = null;
        try {
            PowerManager power = service.getSystemService(PowerManager.class);
            KeyguardManager keyguard = service.getSystemService(KeyguardManager.class);
            if (power == null || !power.isInteractive() || keyguard == null
                    || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) return finish(absent("screen_locked"),windows,root);
            WindowManager manager = service.getSystemService(WindowManager.class);
            if (manager == null) return finish(absent("window_unavailable"),windows,root);
            Rect screen = manager.getCurrentWindowMetrics().getBounds();
            int width = screen.width(), height = screen.height();
            if (width <= 0 || height <= width) return finish(absent("unsupported_orientation"),windows,root);
            windows = service.getWindows();
            if (windows == null || windows.size() > 64) return finish(absent("window_unavailable"),windows,root);
            if(passive && visibleTryOnPreview(service,windows,screen))
                return finish(absent("try_on_preview"),windows,root);
            AccessibilityWindowInfo selected = null, soleApp = null;
            int appWindows = 0;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (!Rect.intersects(bounds, screen)) continue;
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return finish(absent("keyboard_visible"),windows,root);
                // System / accessibility overlays are intentionally ignored, including our non-focusable hint.
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                appWindows++; soleApp = window;
                if (window.isInPictureInPictureMode()) return finish(absent("picture_in_picture"),windows,root);
                if (window.isActive() || window.isFocused()) selected = window;
            }
            if (appWindows != 1) return finish(absent("multiple_app_windows"),windows,root);
            boolean focused=selected!=null;
            if (!focused) selected=soleApp;
            Rect bounds = new Rect(); selected.getBoundsInScreen(bounds);
            if (bounds.width() < width * .90 || bounds.height() < height * .80
                    || Math.abs(bounds.left - screen.left) > width * .05
                    || bounds.top - screen.top > height * .15) return finish(absent("window_not_fullscreen"),windows,root);
            root = selected.getRoot();
            if (root == null || !root.refresh()) return finish(new ReadResult(null,"root_unavailable",selected.getId(),""),windows,root);
            String pkg = string(root.getPackageName());
            if (!SnapshotParser.TIKTOK_PACKAGES.contains(pkg)) return finish(absent("outside_tiktok"),windows,root);
            // Our own non-focusable progress can temporarily hide the active/focused flag.
            // Retain only the sole verified app-window identity; never promote it to a live page.
            if (!focused) return finish(new ReadResult(null,"app_not_focused",selected.getId(),pkg),windows,root);
            List<SnapshotParser.Node> nodes = new ArrayList<>();
            Set<SnapshotParser.Node> seen = new HashSet<>();
            LiveFrameWindow.Builder geometry=withLayout?new LiveFrameWindow.Builder(selected.getId(),bounds):null;
            for (String id : IDS) {
                List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
                collect(matches,nodes,seen,false,height,geometry,id);
            }
            boolean gift = false;
            for (String query : GIFT_QUERIES) {
                List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(query);
                gift |= collect(matches, nodes, seen, true, height);
                if (gift) break;
            }
            if (!gift) collectTopGift(root, nodes, seen, width, height);
            LivePage page = SnapshotParser.parse(pkg, activityName, selected.getId(), selected.getDisplayId(), width, height, nodes);
            OfferOcclusionGuard.Evidence occlusion=offerSource!=null
                ? offerOcclusion(service,windows,selected,root,screen,activityName,offerSource):null;
            return finish(page == null ? new ReadResult(null,"not_live_room",selected.getId(),pkg,null,occlusion)
                : new ReadResult(page,"live_room",page.windowId(),page.packageName(),geometry==null?null:geometry.build(),occlusion),windows,root);
        } catch (RuntimeException unavailable) {
            return finish(absent("snapshot_unavailable"),windows,root);
        } finally {
            if (root != null) root.recycle();
            if (windows != null) for (AccessibilityWindowInfo window : windows) if (window != null) window.recycle();
        }
    }

    /** One read-wide budget; only the Native tree or its trusted SCVH parent may be traversed. */
    private static boolean visibleTryOnPreview(AccessibilityService service,List<AccessibilityWindowInfo> windows,Rect screen) {
        int remaining=MAX_PREVIEW_NODES;
        for(AccessibilityWindowInfo window:windows) {
            if(remaining<=0)break;
            if(window==null || window.getDisplayId()!=Display.DEFAULT_DISPLAY)continue;
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);
            if(bounds.isEmpty() || !Rect.intersects(bounds,screen))continue;
            AccessibilityNodeInfo candidate=null;
            ArrayDeque<PreviewNode> pending=new ArrayDeque<>();
            try {
                candidate=window.getRoot();
                if(candidate==null)continue;
                String owner=string(candidate.getPackageName());
                if(!"org.taku.tryon".equals(owner) && !("org.agentos.assistant".equals(owner) && trustedOwner(service,owner)))continue;
                if(!candidate.refresh())continue;
                pending.add(new PreviewNode(AccessibilityNodeInfo.obtain(candidate),0));
                while(!pending.isEmpty() && remaining>0) {
                    PreviewNode entry=pending.remove();AccessibilityNodeInfo node=entry.node();remaining--;
                    try {
                        if("org.taku.tryon".contentEquals(node.getPackageName()==null?"":node.getPackageName())
                                && node.isVisibleToUser() && !node.isEditable() && !node.isPassword()) {
                            CharSequence description=node.getContentDescription();
                            if(previewDescription(description)) {
                                Rect visible=new Rect();node.getBoundsInScreen(visible);
                                if(!visible.isEmpty() && Rect.intersects(visible,screen))return true;
                            }
                        }
                        if(entry.depth()>=MAX_PREVIEW_DEPTH || node.isEditable() || node.isPassword())continue;
                        int count=Math.min(MAX_PREVIEW_CHILDREN,node.getChildCount());
                        for(int index=0;index<count && pending.size()<remaining;index++) {
                            AccessibilityNodeInfo child=node.getChild(index);
                            if(child!=null)pending.add(new PreviewNode(child,entry.depth()+1));
                        }
                    } finally {node.recycle();}
                }
            } finally {
                while(!pending.isEmpty())pending.remove().node().recycle();
                if(candidate!=null)candidate.recycle();
            }
        }
        return false;
    }
    private record PreviewNode(AccessibilityNodeInfo node,int depth) {}
    private static boolean previewDescription(CharSequence value) {
        if(value==null || value.length()>160)return false;
        String description=value.toString();
        return description.equals("Live try-on window") || description.startsWith("Live cosplay · ");
    }

    private static OfferOcclusionGuard.Evidence offerOcclusion(AccessibilityService service,
            List<AccessibilityWindowInfo> windows,AccessibilityWindowInfo app,AccessibilityNodeInfo root,
            Rect screen,String activityName,LivePage source) {
        String pkg=string(root.getPackageName());
        boolean focused=app.isActive() && app.isFocused() && SnapshotParser.isLiveContainerActivity(activityName);
        if(!focused || app.getId()!=source.windowId() || !pkg.equals(source.packageName()))return null;
        Identity host=identityAnchor(root,source,"wei",screen,false);
        Identity header=identityAnchor(root,source,"ahd",screen,true);
        Identity composer=identityAnchor(root,source,"f7z",screen,false);
        String name=LivePage.canonical(source.hostName());
        boolean sameRoom=!name.isEmpty() && host!=null && name.equals(host.value())
            && header!=null && header.value().contains(name) && composer!=null;
        int ownHints=0;boolean unknown=false;
        for(AccessibilityWindowInfo window:windows) {
            if(window==null || window.getId()==app.getId() || window.getDisplayId()!=Display.DEFAULT_DISPLAY)continue;
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);
            if(!Rect.intersects(bounds,screen))continue;
            AccessibilityNodeInfo overlay=null;
            try {
                overlay=window.getRoot();
                String owner=overlay==null?"":string(overlay.getPackageName());
                boolean passive=window.getType()==AccessibilityWindowInfo.TYPE_SYSTEM
                    && !window.isActive() && !window.isFocused() && !window.isInPictureInPictureMode();
                boolean trusted=trustedOwner(service,owner);
                boolean hint=passive && trusted && window.getLayer()>app.getLayer()
                    && overlay!=null && overlay.refresh()
                    && header!=null && Rect.intersects(bounds,header.bounds())
                    && bounds.left>=screen.left && bounds.right<=screen.right
                    && bounds.width()>=screen.width()*.50 && bounds.height()>=screen.height()*.06
                    && bounds.top>=screen.top && bounds.top<=screen.top+screen.height()*.15
                    && bounds.bottom<=screen.top+screen.height()*.35;
                if(hint)ownHints++;
                else {
                    boolean chrome=passive && "com.android.systemui".equals(owner)
                        && service.getPackageManager().checkSignatures("android",owner)==PackageManager.SIGNATURE_MATCH
                        && (bounds.bottom<=screen.top+screen.height()*.12 || bounds.top>=screen.bottom-screen.height()*.12);
                    if(!chrome)unknown=true;
                }
            } finally {if(overlay!=null)overlay.recycle();}
        }
        return new OfferOcclusionGuard.Evidence(app.getId(),pkg,focused,sameRoom,ownHints,unknown);
    }

    private static boolean trustedOwner(AccessibilityService service,String owner) {
        if(!Set.of("org.agentos.surfaces","org.agentos.assistant").contains(owner))return false;
        PackageManager pm=service.getPackageManager();
        if(pm.checkSignatures("android",owner)!=PackageManager.SIGNATURE_MATCH)return false;
        try {return !"org.agentos.assistant".equals(owner) || pm.getApplicationInfo(owner,0).uid==1000;}
        catch(PackageManager.NameNotFoundException unavailable){return false;}
    }

    /** Raw hidden nodes are compared only with an already verified identity; no LivePage is built. */
    private record Identity(String value,Rect bounds) {}

    private static Identity identityAnchor(AccessibilityNodeInfo root,LivePage source,String id,Rect screen,boolean header) {
        List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByViewId(source.packageName()+":id/"+id);
        if(matches==null)return null;
        try {
            if(matches.size()!=1 || matches.get(0)==null)return null;
            AccessibilityNodeInfo node=matches.get(0);Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if(!source.packageName().equals(string(node.getPackageName())) || node.getWindowId()!=source.windowId()
                    || bounds.isEmpty() || !Rect.intersects(bounds,screen)
                    || (id.equals("f7z")?bounds.top<screen.height()*.75:bounds.top>=screen.height()*.22))return null;
            if(id.equals("f7z"))return new Identity("composer",bounds); // Never read unsent or editable text.
            if(node.isEditable() || node.isPassword())return null;
            return new Identity(LivePage.canonical(string(node.getText())+(header?" "+string(node.getContentDescription()):"")),bounds);
        } finally {for(AccessibilityNodeInfo node:matches)if(node!=null)node.recycle();}
    }

    /** Called before recycling the exact list/root used by this read. Diagnostic failures never change its result. */
    private static ReadResult finish(ReadResult result,List<AccessibilityWindowInfo> windows,AccessibilityNodeInfo selectedRoot) {
        try {
            List<WindowReadDiagnostic.Window> entries=new ArrayList<>();int total=0;
            if(windows!=null)for(AccessibilityWindowInfo window:windows) {
                if(window==null||window.getDisplayId()!=Display.DEFAULT_DISPLAY)continue;
                total++;
                if(entries.size()>=WindowReadDiagnostic.MAX_WINDOWS)continue;
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);
                WindowReadDiagnostic.PackageKind kind=WindowReadDiagnostic.PackageKind.UNKNOWN;
                AccessibilityNodeInfo extraRoot=null;
                try {
                    AccessibilityNodeInfo known=selectedRoot!=null&&selectedRoot.getWindowId()==window.getId()?selectedRoot:null;
                    if(known==null){extraRoot=window.getRoot();known=extraRoot;}
                    if(known!=null)kind=WindowReadDiagnostic.packageKind(known.getPackageName());
                } catch(RuntimeException unavailable) { /* The window's scalar evidence remains useful. */ }
                finally {if(extraRoot!=null&&extraRoot!=selectedRoot)extraRoot.recycle();}
                entries.add(new WindowReadDiagnostic.Window(window.getId(),window.getType(),window.getLayer(),
                    window.isActive(),window.isFocused(),window.isAccessibilityFocused(),window.isInPictureInPictureMode(),
                    bounds.left,bounds.top,bounds.right,bounds.bottom,kind));
            }
            lastDiagnostic=WindowReadDiagnostic.render(result.reason(),entries,total);
        } catch(RuntimeException|OutOfMemoryError unavailable) {
            lastDiagnostic="{\"schema\":\"taku.window-read.v1\",\"reason\":\"snapshot_unavailable\",\"defaultWindowCount\":0,\"truncated\":false,\"windows\":[]}";
        }
        return result;
    }

    private static boolean collect(List<AccessibilityNodeInfo> found, List<SnapshotParser.Node> nodes,
                                   Set<SnapshotParser.Node> seen, boolean giftOnly, int height) {
        return collect(found,nodes,seen,giftOnly,height,null,"");
    }
    private static boolean collect(List<AccessibilityNodeInfo> found,List<SnapshotParser.Node> nodes,
                                   Set<SnapshotParser.Node> seen,boolean giftOnly,int height,
                                   LiveFrameWindow.Builder geometry,String id) {
        if (found == null) return false;
        boolean gift = false;
        int count = 0;
        try {
            // Geometry and page consume precisely the same raw query results; this finally owns recycling.
            if(geometry!=null)geometry.include(id,found);
            for (AccessibilityNodeInfo node : found) {
                if (node == null) continue;
                if (++count > 64 || !node.isVisibleToUser()) continue;
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (giftOnly && !topLabel(bounds, height)) continue;
                SnapshotParser.Node converted = convert(node, bounds);
                if (giftOnly && !giftLabel(converted)) continue;
                if (seen.add(converted)) nodes.add(converted);
                gift |= giftOnly;
            }
        } finally { for (AccessibilityNodeInfo node : found) if (node != null) node.recycle(); }
        return gift;
    }

    private static void collectTopGift(AccessibilityNodeInfo root, List<SnapshotParser.Node> nodes,
                                      Set<SnapshotParser.Node> seen, int width, int height) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int acquired = 1;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    if (!node.isVisibleToUser() || !overlapsTop(bounds, width, height)) continue;
                    if (topLabel(bounds, height)) {
                        SnapshotParser.Node converted = convert(node, bounds);
                        if (giftLabel(converted) && seen.add(converted)) { nodes.add(converted); return; }
                    }
                    for (int i = 0; i < node.getChildCount() && acquired < MAX_GIFT_NODES; i++) {
                        acquired++;
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null) continue;
                        boolean queued = false;
                        try {
                            Rect childBounds = new Rect(); child.getBoundsInScreen(childBounds);
                            if (child.isVisibleToUser() && overlapsTop(childBounds, width, height)) {
                                queue.addLast(child); queued = true;
                            }
                        } finally { if (!queued) child.recycle(); }
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!queue.isEmpty()) queue.removeFirst().recycle(); }
    }

    private static boolean overlapsTop(Rect bounds, int width, int height) {
        return bounds.left < width && bounds.right > 0 && bounds.top < height * .25 && bounds.bottom > 0;
    }

    private static boolean topLabel(Rect bounds, int height) {
        return bounds.top >= 0 && bounds.top < height * .25 && bounds.bottom <= height * .30;
    }

    private static SnapshotParser.Node convert(AccessibilityNodeInfo node, Rect bounds) {
        String id = string(node.getViewIdResourceName());
        // f7z is an identity anchor only: never copy an unsent reply, password, or editable value.
        boolean privateInput = node.isEditable() || node.isPassword() || id.endsWith("/f7z");
        return new SnapshotParser.Node(string(node.getPackageName()), string(node.getClassName()),
            privateInput ? "" : string(node.getText()), privateInput ? "" : string(node.getContentDescription()),
            id, bounds.left, bounds.top, bounds.right, bounds.bottom, node.isVisibleToUser());
    }

    private static boolean giftLabel(SnapshotParser.Node node) {
        String label = LivePage.canonical(node.text().isBlank() ? node.description() : node.text());
        return Set.of("禮物庫", "礼物库", "gift gallery", "gift vault", "gift library").contains(label);
    }
    private static ReadResult absent(String reason) { return new ReadResult(null, reason); }
    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
}
