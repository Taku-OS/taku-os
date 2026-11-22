// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Current TikTok adapter geometry. The actual native product bar is excluded, never used as a reference. */
final class LiveFrameWindow {
    private static final java.util.Set<String> IDS=java.util.Set.of("ahd","d1i","mr1","f62","f5p","f4m");
    record Layout(int windowId,int left,int top,int right,int bottom,int contentTop,int contentBottom) {
        boolean allows(LiveFrameTarget.Bounds bounds) {
            int height=bottom-top;
            return bounds.withinVideo(contentTop-top,contentBottom-top,height);
        }
    }
    /** Geometry collected alongside the verified page, before the same root/nodes are recycled. */
    static final class Builder {
        private final int windowId;
        private final Rect bounds;
        private int start,end;
        private boolean valid=true;
        Builder(int windowId,Rect bounds) {
            this.windowId=windowId;this.bounds=new Rect(bounds);
            start=bounds.top;end=bounds.bottom;
            valid=bounds.width()>0&&bounds.height()>bounds.width();
        }
        void include(String id,List<AccessibilityNodeInfo> found) {
            if(!IDS.contains(id)||found==null)return;
            // Count the raw matches, including invisible/duplicate nodes, just as the standalone read did.
            if(found.size()>16){valid=false;return;}
            for(AccessibilityNodeInfo node:found)if(node!=null&&node.isVisibleToUser()) {
                Rect box=new Rect();node.getBoundsInScreen(box);
                if(box.isEmpty()||!bounds.contains(box)){valid=false;continue;}
                if(id.startsWith("f")) {
                    if(box.top<bounds.top+bounds.height()/2){valid=false;continue;}
                    end=Math.min(end,box.top);
                } else start=Math.max(start,box.bottom);
            }
        }
        Layout build() {
            return !valid||end-start<256?null:new Layout(windowId,bounds.left,bounds.top,bounds.right,bounds.bottom,start,end);
        }
    }
    static Layout read(AccessibilityService service,LivePage page) {
        if(page==null)return null;
        List<AccessibilityWindowInfo> windows=null;AccessibilityNodeInfo root=null;
        try {
            windows=service.getWindows();if(windows==null)return null;
            AccessibilityWindowInfo selected=null;
            for(AccessibilityWindowInfo window:windows)if(window!=null&&window.getDisplayId()==Display.DEFAULT_DISPLAY
                    &&window.getId()==page.windowId()&&(window.isActive()||window.isFocused()))selected=window;
            if(selected==null)return null;
            Rect bounds=new Rect();selected.getBoundsInScreen(bounds);
            if(bounds.width()<=0||bounds.height()<=bounds.width())return null;
            root=selected.getRoot();if(root==null||!root.refresh()||!page.packageName().contentEquals(root.getPackageName()))return null;
            Builder geometry=new Builder(page.windowId(),bounds);
            for(String id:IDS) {
                List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByViewId(page.packageName()+":id/"+id);
                if(found==null)continue;
                try {
                    geometry.include(id,found);
                } finally {for(AccessibilityNodeInfo node:found)if(node!=null)node.recycle();}
            }
            return geometry.build();
        } catch(RuntimeException unreadable){return null;}
        finally {if(root!=null)root.recycle();if(windows!=null)for(AccessibilityWindowInfo window:windows)if(window!=null)window.recycle();}
    }
    private LiveFrameWindow(){}
}
