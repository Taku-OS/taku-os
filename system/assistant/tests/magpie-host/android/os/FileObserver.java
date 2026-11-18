package android.os;
import java.io.File;
public abstract class FileObserver { public static final int CLOSE_WRITE=8,MOVED_TO=128; public FileObserver(File f,int m){} public abstract void onEvent(int e,String p); public void startWatching(){} }
