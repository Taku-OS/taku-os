package de.robv.android.xposed;

public class XC_MethodHook {
    public static final class Unhook {
        public void unhook() { XposedBridge.removed++; }
    }
}
