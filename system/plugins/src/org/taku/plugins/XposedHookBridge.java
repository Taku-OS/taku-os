package org.taku.plugins;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

public final class XposedHookBridge {
    private XposedHookBridge() { }
    public static AutoCloseable install(Member target, Object callback, ClassLoader frameworkLoader) throws ReflectiveOperationException {
        Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge", true, frameworkLoader);
        Class<?> callbackType = Class.forName("de.robv.android.xposed.XC_MethodHook", true, frameworkLoader);
        if (!callbackType.isInstance(callback)) throw new IllegalArgumentException("callback must be XC_MethodHook");
        Method method = bridge.getMethod("hookMethod", Member.class, callbackType);
        Object unhook = method.invoke(null, target, callback);
        Method remove = unhook.getClass().getMethod("unhook");
        return () -> remove.invoke(unhook);
    }
}
