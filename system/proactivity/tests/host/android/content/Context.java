package android.content;

import java.io.File;

/** Host-test filesystem only; no Android runtime or device connection. */
public final class Context {
    private final File files;
    public Context(File files){this.files=files;}
    public File getFilesDir(){return files;}
}
