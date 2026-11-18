package org.taku.plugins.host;

import dalvik.system.DexClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.taku.plugins.TakuPlugin;

public final class AndroidPluginLoader implements PluginLoader {
    public ClassLoader create(Path code, Path optimizedDirectory) throws Exception {
        Files.createDirectories(optimizedDirectory);
        Path archive = code;
        if (code.getFileName().toString().endsWith(".dex")) {
            archive = optimizedDirectory.resolve("plugin-dex.jar");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry("classes.dex"));
                Files.copy(code, output);
                output.closeEntry();
            }
        }
        return new DexClassLoader(archive.toString(), optimizedDirectory.toString(), null, TakuPlugin.class.getClassLoader());
    }
}
