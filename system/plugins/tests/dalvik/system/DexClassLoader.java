package dalvik.system;

public final class DexClassLoader extends ClassLoader {
    public static int created;
    public DexClassLoader(String dex, String optimized, String libraries, ClassLoader parent) {
        super(parent);
        created++;
    }
}
