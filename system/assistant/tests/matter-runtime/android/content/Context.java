package android.content;

import java.io.File;
import android.app.KeyguardManager;
import android.os.UserManager;

/** Host adapter for the two device-lock facts consulted by MatterRuntime. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final java.util.Map<String, SharedPreferences> preferences = new java.util.HashMap<>();
    private final File files;
    public Context(File files) {this.files=files;}
    public Context getApplicationContext() {return this;}
    public File getFilesDir() {return files;}
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences.computeIfAbsent(name, key -> new org.agentos.assistant.MemoryPreferences());
    }
    public <T> T getSystemService(Class<T> type) {
        if(type==UserManager.class)return type.cast(new UserManager());
        if(type==KeyguardManager.class)return type.cast(new KeyguardManager());
        throw new IllegalArgumentException(type.getName());
    }
}
