package org.taku.plugins;

import dalvik.system.DexClassLoader;
import java.io.File;

public final class DexPluginLoader {
    private TakuPlugin plugin;
    private ClassLoader classLoader;
    private File dexFile;
    private File optimizedDirectory;
    private String entryClass;
    private PluginContext context;
    private String state = "unloaded";
    private String error;

    public synchronized void load(File dex, File optimizedDirectory, String className,
            PluginContext context) throws Exception {
        if (plugin != null) throw new IllegalStateException("already loaded");
        try {
            ClassLoader loader = new DexClassLoader(dex.getAbsolutePath(),
                    optimizedDirectory.getAbsolutePath(), null, TakuPlugin.class.getClassLoader());
            classLoader = loader;
            TakuPlugin candidate = (TakuPlugin) loader.loadClass(className)
                    .getDeclaredConstructor().newInstance();
            candidate.onLoad(context);
            plugin = candidate;
            dexFile = dex;
            this.optimizedDirectory = optimizedDirectory;
            entryClass = className;
            this.context = context;
            state = "loaded";
            error = null;
        } catch (Exception | LinkageError | AssertionError failure) {
            closeLoader();
            state = "error";
            error = failure.toString();
            throw failure;
        }
    }

    public synchronized void start() throws Exception { invoke("start"); }
    public synchronized void stop() throws Exception { invoke("stop"); }
    public synchronized void reload() throws Exception {
        if (plugin == null) throw new IllegalStateException("not loaded");
        boolean started = state.equals("started");
        File dex = dexFile;
        File optimized = optimizedDirectory;
        String className = entryClass;
        PluginContext savedContext = context;
        if (started) stop();
        plugin = null;
        closeLoader();
        state = "unloaded";
        load(dex, optimized, className, savedContext);
        if (started) start();
    }
    public synchronized void uninstall() throws Exception { invoke("uninstall"); }
    public synchronized String state() { return state; }
    public synchronized String error() { return error; }

    private void invoke(String action) throws Exception {
        if (plugin == null) throw new IllegalStateException("not loaded");
        if (action.equals("start") && !state.equals("loaded") && !state.equals("stopped"))
            throw new IllegalStateException("start requires loaded or stopped");
        if (action.equals("stop") && !state.equals("started"))
            throw new IllegalStateException("stop requires started");
        if (action.equals("uninstall") && state.equals("started"))
            throw new IllegalStateException("stop before uninstall");
        try {
            switch (action) {
                case "start": plugin.onStart(); state = "started"; break;
                case "stop": plugin.onStop(); state = "stopped"; break;
                case "uninstall": plugin.onUninstall(); plugin = null; closeLoader(); state = "unloaded"; break;
                default: throw new IllegalArgumentException(action);
            }
            error = null;
        } catch (Exception | LinkageError | AssertionError failure) {
            state = "error";
            error = failure.toString();
            throw failure;
        }
    }

    private void closeLoader() throws java.io.IOException {
        ClassLoader loader = classLoader;
        classLoader = null;
        if (loader instanceof java.io.Closeable) ((java.io.Closeable)loader).close();
    }
}
