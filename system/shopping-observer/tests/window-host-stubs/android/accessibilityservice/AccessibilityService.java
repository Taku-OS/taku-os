// SPDX-License-Identifier: Apache-2.0
package android.accessibilityservice;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AccessibilityService {
    public final Map<Class<?>,Object> services=new HashMap<>();
    public final List<AccessibilityWindowInfo> windows=new ArrayList<>();
    public final android.content.pm.PackageManager packages=new android.content.pm.PackageManager();
    public int windowReads;
    public boolean windowsUnavailable;
    public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
    public android.content.pm.PackageManager getPackageManager(){return packages;}
    public List<AccessibilityWindowInfo> getWindows(){
        windowReads++;
        if(windowsUnavailable)return null;
        List<AccessibilityWindowInfo> result=new ArrayList<>();
        for(var window:windows)result.add(window==null?null:AccessibilityWindowInfo.obtain(window));
        return result;
    }
}
