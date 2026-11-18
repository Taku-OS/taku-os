import java.io.File;
import org.taku.plugins.DexPluginLoader;
import org.taku.plugins.PluginContext;
import org.taku.plugins.TakuPlugin;

public final class LoaderTest {
    public static final class Example implements TakuPlugin {
        static int loads, starts, stops, reloads, uninstalls;
        public void onLoad(PluginContext context) { loads++; }
        public void onStart() { starts++; }
        public void onStop() { stops++; }
        public void onReload() { reloads++; }
        public void onUninstall() { uninstalls++; }
    }
    public static final class Broken implements TakuPlugin {
        public void onLoad(PluginContext context) { }
        public void onStart() { throw new IllegalStateException("broken plugin"); }
        public void onStop() { }
        public void onReload() { }
        public void onUninstall() { }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        DexPluginLoader loader = new DexPluginLoader();
        loader.load(new File("unused.dex"), new File("."), Example.class.getName(), null);
        check(loader.state().equals("loaded") && Example.loads == 1);
        loader.start();
        check(loader.state().equals("started") && Example.starts == 1);
        try { loader.uninstall(); throw new AssertionError(); } catch (IllegalStateException expected) { }
        loader.reload();
        loader.stop();
        loader.uninstall();
        check(loader.state().equals("unloaded") && Example.loads == 2 && Example.starts == 2 && Example.reloads == 0 && Example.stops == 2 && Example.uninstalls == 1 && dalvik.system.DexClassLoader.created == 2);
        loader.load(new File("unused.dex"), new File("."), Broken.class.getName(), null);
        try { loader.start(); throw new AssertionError(); } catch (IllegalStateException expected) { }
        check(loader.state().equals("error") && loader.error().contains("broken plugin"));
        AutoCloseable hook = org.taku.plugins.XposedHookBridge.install(Example.class.getMethod("onStart"), new de.robv.android.xposed.XC_MethodHook(), LoaderTest.class.getClassLoader());
        hook.close();
        check(de.robv.android.xposed.XposedBridge.installed == 1 && de.robv.android.xposed.XposedBridge.removed == 1);
        System.out.println("PASS: loader lifecycle and error state (host classloader stub)");
    }
}
