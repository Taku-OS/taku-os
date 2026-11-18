package org.taku.plugins;

public interface HookContext {
    interface Callback {
        void invoke(Invocation invocation) throws Throwable;
    }
    interface Invocation {
        Object receiver();
        Object[] arguments();
        Object result();
        void setResult(Object value);
        Throwable error();
    }
    AutoCloseable hook(String className, String methodName, String[] parameterTypes,
            Callback before, Callback after) throws Exception;
}
