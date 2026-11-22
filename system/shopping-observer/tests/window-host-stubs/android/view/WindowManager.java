// SPDX-License-Identifier: Apache-2.0
package android.view;
import android.graphics.Rect;
public final class WindowManager {
    public Rect bounds=new Rect(0,0,1080,2424);
    public WindowMetrics getCurrentWindowMetrics(){return new WindowMetrics(bounds);}
    public static final class WindowMetrics {
        private final Rect bounds;
        WindowMetrics(Rect bounds){this.bounds=bounds;}
        public Rect getBounds(){return new Rect(bounds);}
    }
}
