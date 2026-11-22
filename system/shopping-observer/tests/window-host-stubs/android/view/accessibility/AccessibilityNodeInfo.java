// SPDX-License-Identifier: Apache-2.0
package android.view.accessibility;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Each returned handle has independent recycling; the model stays mutable between fresh reads. */
public final class AccessibilityNodeInfo {
    public String packageName="com.zhiliaoapp.musically",className="android.view.View",text="",description="",viewId="";
    public int windowId=100,refreshes,recycles,textQueries;
    public boolean visible=true,editable,password,refreshable=true,throwBounds;
    public Rect bounds=new Rect(0,0,1080,2424);
    public final Map<String,List<AccessibilityNodeInfo>> ids=new HashMap<>(),texts=new HashMap<>();
    public final Map<String,Integer> idQueries=new HashMap<>();
    public final List<AccessibilityNodeInfo> children=new ArrayList<>();
    private final AccessibilityNodeInfo model;
    private boolean recycled;
    public AccessibilityNodeInfo(){model=this;}
    private AccessibilityNodeInfo(AccessibilityNodeInfo other){model=other.model;}
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo other){return new AccessibilityNodeInfo(other);}
    public boolean refresh(){model.refreshes++;return model.refreshable;}
    public int getWindowId(){return model.windowId;}
    public CharSequence getPackageName(){return model.packageName;}
    public CharSequence getClassName(){return model.className;}
    public CharSequence getText(){return model.text;}
    public CharSequence getContentDescription(){return model.description;}
    public String getViewIdResourceName(){return model.viewId;}
    public boolean isVisibleToUser(){return model.visible;}
    public boolean isEditable(){return model.editable;}
    public boolean isPassword(){return model.password;}
    public void getBoundsInScreen(Rect out){if(model.throwBounds)throw new IllegalStateException("unavailable bounds");out.set(model.bounds);}
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id){
        model.idQueries.merge(id,1,Integer::sum);return handles(model.ids.get(id));
    }
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text){model.textQueries++;return handles(model.texts.get(text));}
    public int getChildCount(){return model.children.size();}
    public AccessibilityNodeInfo getChild(int index){return obtain(model.children.get(index));}
    public void recycle(){if(recycled)throw new AssertionError("Node recycled twice");recycled=true;model.recycles++;}
    private static List<AccessibilityNodeInfo> handles(List<AccessibilityNodeInfo> models){
        List<AccessibilityNodeInfo> result=new ArrayList<>();
        if(models!=null)for(var node:models)result.add(node==null?null:obtain(node));
        return result;
    }
}
