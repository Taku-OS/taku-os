// SPDX-License-Identifier: Apache-2.0
package android.view.accessibility;
import android.graphics.Rect;

public final class AccessibilityWindowInfo {
    public static final int TYPE_APPLICATION=1,TYPE_INPUT_METHOD=2,TYPE_SYSTEM=3,TYPE_ACCESSIBILITY_OVERLAY=4;
    public int id=100,type=TYPE_APPLICATION,displayId,layer,rootRequests,recycles;
    public boolean active=true,focused=true,accessibilityFocused,pip;
    public Rect bounds=new Rect(0,0,1080,2424);
    public AccessibilityNodeInfo root;
    private final AccessibilityWindowInfo model;
    private boolean recycled;
    public AccessibilityWindowInfo(){model=this;}
    private AccessibilityWindowInfo(AccessibilityWindowInfo model){this.model=model.model;}
    public static AccessibilityWindowInfo obtain(AccessibilityWindowInfo other){return new AccessibilityWindowInfo(other);}
    public int getId(){return model.id;}
    public int getType(){return model.type;}
    public int getDisplayId(){return model.displayId;}
    public int getLayer(){return model.layer;}
    public boolean isActive(){return model.active;}
    public boolean isFocused(){return model.focused;}
    public boolean isAccessibilityFocused(){return model.accessibilityFocused;}
    public boolean isInPictureInPictureMode(){return model.pip;}
    public void getBoundsInScreen(Rect out){out.set(model.bounds);}
    public AccessibilityNodeInfo getRoot(){model.rootRequests++;return model.root==null?null:AccessibilityNodeInfo.obtain(model.root);}
    public void recycle(){if(recycled)throw new AssertionError("Window recycled twice");recycled=true;model.recycles++;}
}
