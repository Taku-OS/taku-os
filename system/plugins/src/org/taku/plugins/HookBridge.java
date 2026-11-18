package org.taku.plugins;

public final class HookBridge implements HookContext {
    public interface Backend {
        AutoCloseable install(String className, String methodName, String[] parameterTypes,
                Callback before, Callback after) throws Exception;
    }

    private final Backend backend;

    public HookBridge(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("hook backend required");
        this.backend = backend;
    }

    @Override
    public AutoCloseable hook(String className, String methodName, String[] parameterTypes,
            Callback before, Callback after) throws Exception {
        return backend.install(className, methodName, parameterTypes, before, after);
    }
}
