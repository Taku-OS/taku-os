package android.util;

import java.io.*;
import java.nio.file.*;

/** File-backed test substitute; Android's own atomic-write implementation is not under test. */
public final class AtomicFile {
    private final File base,pending;
    public AtomicFile(File base){this.base=base;pending=new File(base+".pending");}
    public File getBaseFile(){return base;}
    public FileInputStream openRead()throws IOException{return new FileInputStream(base);}
    public FileOutputStream startWrite()throws IOException{return new FileOutputStream(pending);}
    public void finishWrite(FileOutputStream stream)throws IOException{
        stream.close();Files.move(pending.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    public void failWrite(FileOutputStream stream)throws IOException{stream.close();Files.deleteIfExists(pending.toPath());}
}
