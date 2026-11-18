package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    public static int installed, removed;
    public static XC_MethodHook.Unhook hookMethod(Member target, XC_MethodHook callback) {
        installed++;
        return new XC_MethodHook.Unhook();
    }
}
