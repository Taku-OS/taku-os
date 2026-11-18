package org.taku.plugins;

public final class HookBackendLoader {
    private HookBackendLoader() { }

    public static HookContext load(String adapterClass, String requiredFrameworkClass,
            ClassLoader loader) throws ReflectiveOperationException {
        Class.forName(requiredFrameworkClass, false, loader);
        Object adapter = Class.forName(adapterClass, true, loader).getDeclaredConstructor().newInstance();
        if (!(adapter instanceof HookBridge.Backend)) throw new IllegalArgumentException("adapter must implement HookBridge.Backend");
        return new HookBridge((HookBridge.Backend)adapter);
    }
}
